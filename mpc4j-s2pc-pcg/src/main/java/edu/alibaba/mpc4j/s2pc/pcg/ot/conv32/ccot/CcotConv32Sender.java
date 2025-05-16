package edu.alibaba.mpc4j.s2pc.pcg.ot.conv32.ccot;

import edu.alibaba.mpc4j.common.rpc.*;
import edu.alibaba.mpc4j.common.tool.bitvector.BitVector;
import edu.alibaba.mpc4j.common.tool.bitvector.BitVectorFactory;
import edu.alibaba.mpc4j.common.tool.crypto.crhf.CrhfFactory.CrhfType;
import edu.alibaba.mpc4j.common.tool.utils.BlockUtils;
import edu.alibaba.mpc4j.common.tool.utils.BytesUtils;
import edu.alibaba.mpc4j.s2pc.pcg.ot.conv32.AbstractConv32Party;
import edu.alibaba.mpc4j.s2pc.pcg.ot.conv32.ccot.CcotConv32PtoDesc.PtoStep;
import edu.alibaba.mpc4j.s2pc.pcg.ot.cot.CotSenderOutput;
import edu.alibaba.mpc4j.s2pc.pcg.ot.cot.RotSenderOutput;
import edu.alibaba.mpc4j.s2pc.pcg.ot.cot.core.CoreCotConfig;
import edu.alibaba.mpc4j.s2pc.pcg.ot.cot.core.CoreCotFactory;
import edu.alibaba.mpc4j.s2pc.pcg.ot.cot.core.CoreCotSender;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * F_3 -> F_2 modulus conversion using Core COT sender.
 *
 * @author Weiran Liu
 * @date 2024/10/10
 */
public class CcotConv32Sender extends AbstractConv32Party {
    /**
     * core COT sender
     */
    private final CoreCotSender coreCotSender;

    public CcotConv32Sender(Rpc senderRpc, Party receiverParty, CcotConv32Config config) {
        super(CcotConv32PtoDesc.getInstance(), senderRpc, receiverParty, config);
        CoreCotConfig coreCotConfig = config.getCoreCotConfig();
        coreCotSender = CoreCotFactory.createSender(senderRpc, receiverParty, coreCotConfig);
        addSubPto(coreCotSender);
    }

    @Override
    public void init(int expectNum) throws MpcAbortException {
        setInitInput(expectNum);
        logPhaseInfo(PtoState.INIT_BEGIN);

        stopWatch.start();
        byte[] delta = BlockUtils.randomBlock(secureRandom);
        coreCotSender.init(delta);
        stopWatch.stop();
        long initTime = stopWatch.getTime(TimeUnit.MILLISECONDS);
        stopWatch.reset();
        logStepInfo(PtoState.INIT_STEP, 1, 1, initTime);

        logPhaseInfo(PtoState.INIT_END);
    }

    @Override
    public void init() throws MpcAbortException {
        // each conversion needs 2 COTs
        init(1 << 29);
    }

    @Override
    public byte[] conv(byte[] w0) throws MpcAbortException {
        setPtoInput(w0);
        logPhaseInfo(PtoState.PTO_BEGIN);

        // two parties generate (x_0, x_1, y_0, y_1), (x′_0, x′_1, y′_0, y'_1), where y_1 = w_{1,0} and y'_1 = w_{1,1}
        // we follow f^{ab} of [ALSZ13]
        stopWatch.start();
        // run first COT
        CotSenderOutput cotSenderOutput = coreCotSender.send(num);
        RotSenderOutput rotSenderOutput = new RotSenderOutput(envType, CrhfType.MMO, cotSenderOutput);
        byte[][] r0Array = rotSenderOutput.getR0Array();
        byte[][] r1Array = rotSenderOutput.getR1Array();
        byte[] x0ByteArray = BytesUtils.extractLsb(r0Array);
        byte[] y0ByteArray = BytesUtils.extractLsb(r1Array);
        BitVector x0 = BitVectorFactory.create(num, x0ByteArray);
        BitVector y0 = BitVectorFactory.create(num, y0ByteArray);
        // x_0 = R_0, y_0 = R_0 ⊕ R_1
        y0.xori(x0);
        // run second COT
        cotSenderOutput = coreCotSender.send(num);
        rotSenderOutput = new RotSenderOutput(envType, CrhfType.MMO, cotSenderOutput);
        r0Array = rotSenderOutput.getR0Array();
        r1Array = rotSenderOutput.getR1Array();
        byte[] x0pByteArray = BytesUtils.extractLsb(r0Array);
        byte[] y0pByteArray = BytesUtils.extractLsb(r1Array);
        BitVector x0p = BitVectorFactory.create(num, x0pByteArray);
        BitVector y0p = BitVectorFactory.create(num, y0pByteArray);
        // x'_0 = R_0, y'_0 = R_0 ⊕ R_1
        y0p.xori(x0p);
        long roundTime = stopWatch.getTime(TimeUnit.MILLISECONDS);
        stopWatch.reset();
        logStepInfo(PtoState.PTO_STEP, 1, 3, roundTime, "Parties generate 1-out-of-2 bit ROT");

        stopWatch.start();
        // decompose w0
        BitVector w00 = BitVectorFactory.createZeros(num);
        BitVector w01 = BitVectorFactory.createZeros(num);
        IntStream.range(0, num).forEach(i -> {
            w00.set(i, (w0[i] & 0b00000001) != 0);
            w01.set(i, (w0[i] & 0b00000010) != 0);
        });
        // m_0 = x_0 ⊕ x′_0
        BitVector m0 = x0.xor(x0p);
        // m_1 = x_0 ⊕ y_0 ⊕ x′_0
        BitVector m1 = m0.xor(y0);
        // m_2 = x_0 ⊕ x′_0 ⊕ y′_0
        BitVector m2 = m0.xor(y0p);
        // P0 computes v_0 = w_{0,0} ⊕ m_0.
        BitVector v0 = w00.xor(m0);
        // P0 computes t_0 = v_0 ⊕ m_1 ⊕ w_{0,0} ⊕ w_{0,1} ⊕ 1
        BitVector t0 = v0.xor(m1);
        t0.xori(w00);
        t0.xori(w01);
        t0.noti();
        // P0 computes t_1 = v_0 ⊕ m_2 ⊕ w_{0,1}
        BitVector t1 = v0.xor(m2);
        t1.xori(w01);
        // P0 sends (t_0, t_1) to P1.
        List<byte[]> t0t1Payload = new LinkedList<>();
        t0t1Payload.add(t0.getBytes());
        t0t1Payload.add(t1.getBytes());
        sendOtherPartyPayload(PtoStep.SENDER_SEND_T0_T1.ordinal(), t0t1Payload);
        stopWatch.stop();
        long shareTime = stopWatch.getTime(TimeUnit.MILLISECONDS);
        stopWatch.reset();
        logStepInfo(PtoState.PTO_STEP, 3, 3, shareTime);

        logPhaseInfo(PtoState.PTO_END);
        // P0 outputs v0
        return v0.getBytes();
    }
}
