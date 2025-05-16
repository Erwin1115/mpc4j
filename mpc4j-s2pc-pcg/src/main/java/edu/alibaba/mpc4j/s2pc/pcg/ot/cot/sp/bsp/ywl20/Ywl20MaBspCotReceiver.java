package edu.alibaba.mpc4j.s2pc.pcg.ot.cot.sp.bsp.ywl20;

import edu.alibaba.mpc4j.common.rpc.*;
import edu.alibaba.mpc4j.common.rpc.utils.DataPacket;
import edu.alibaba.mpc4j.common.rpc.utils.DataPacketHeader;
import edu.alibaba.mpc4j.common.tool.CommonConstants;
import edu.alibaba.mpc4j.common.tool.crypto.hash.Hash;
import edu.alibaba.mpc4j.common.tool.crypto.hash.HashFactory;
import edu.alibaba.mpc4j.common.tool.crypto.prf.Prf;
import edu.alibaba.mpc4j.common.tool.crypto.prf.PrfFactory;
import edu.alibaba.mpc4j.common.tool.galoisfield.gf2k.Gf2k;
import edu.alibaba.mpc4j.common.tool.galoisfield.gf2k.Gf2kFactory;
import edu.alibaba.mpc4j.common.tool.utils.BinaryUtils;
import edu.alibaba.mpc4j.common.tool.utils.BlockUtils;
import edu.alibaba.mpc4j.s2pc.pcg.dpprf.rdpprf.bp.BpRdpprfConfig;
import edu.alibaba.mpc4j.s2pc.pcg.dpprf.rdpprf.bp.BpRdpprfFactory;
import edu.alibaba.mpc4j.s2pc.pcg.dpprf.rdpprf.bp.BpRdpprfReceiver;
import edu.alibaba.mpc4j.s2pc.pcg.dpprf.rdpprf.bp.BpRdpprfReceiverOutput;
import edu.alibaba.mpc4j.s2pc.pcg.ot.cot.sp.bsp.AbstractBspCotReceiver;
import edu.alibaba.mpc4j.s2pc.pcg.ot.cot.sp.bsp.BspCotReceiverOutput;
import edu.alibaba.mpc4j.s2pc.pcg.ot.cot.sp.bsp.ywl20.Ywl20MaBspCotPtoDesc.PtoStep;
import edu.alibaba.mpc4j.s2pc.pcg.ot.cot.sp.ssp.SspCotReceiverOutput;
import edu.alibaba.mpc4j.s2pc.pcg.ot.cot.core.CoreCotFactory;
import edu.alibaba.mpc4j.s2pc.pcg.ot.cot.core.CoreCotReceiver;
import edu.alibaba.mpc4j.s2pc.pcg.ot.cot.CotReceiverOutput;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * malicious YWL20-BSP-COT receiver.
 *
 * @author Weiran Liu
 * @date 2022/6/7
 */
public class Ywl20MaBspCotReceiver extends AbstractBspCotReceiver {
    /**
     * BP-RDPPRF config
     */
    private final BpRdpprfConfig bpRdpprfConfig;
    /**
     * core COT
     */
    private final CoreCotReceiver coreCotReceiver;
    /**
     * BP-DPPRF
     */
    private final BpRdpprfReceiver bpRdpprfReceiver;
    /**
     * GF(2^128) instance
     */
    private final Gf2k gf2k;
    /**
     * H': F_{2^κ} → {0,1}^{2κ} modeled as a random oracle.
     */
    private final Hash hash;
    /**
     * COT receiver output
     */
    private CotReceiverOutput cotReceiverOutput;
    /**
     * check COT receiver output
     */
    private CotReceiverOutput checkCotReceiverOutput;
    /**
     * BP-DPPRF receiver output
     */
    private BpRdpprfReceiverOutput bpRdpprfReceiverOutput;
    /**
     * random oracle
     */
    private Prf randomOracle;

    public Ywl20MaBspCotReceiver(Rpc receiverRpc, Party senderParty, Ywl20MaBspCotConfig config) {
        super(Ywl20MaBspCotPtoDesc.getInstance(), receiverRpc, senderParty, config);
        coreCotReceiver = CoreCotFactory.createReceiver(receiverRpc, senderParty, config.getCoreCotConfig());
        addSubPto(coreCotReceiver);
        bpRdpprfConfig = config.getBpDpprfConfig();
        bpRdpprfReceiver = BpRdpprfFactory.createReceiver(receiverRpc, senderParty, bpRdpprfConfig);
        addSubPto(bpRdpprfReceiver);
        gf2k = Gf2kFactory.createInstance(envType);
        hash = HashFactory.createInstance(envType, 2 * CommonConstants.BLOCK_BYTE_LENGTH);
    }

    @Override
    public void init() throws MpcAbortException {
        setInitInput();
        logPhaseInfo(PtoState.INIT_BEGIN);

        stopWatch.start();
        coreCotReceiver.init();
        bpRdpprfReceiver.init();
        stopWatch.stop();
        long initTime = stopWatch.getTime(TimeUnit.MILLISECONDS);
        stopWatch.reset();
        logStepInfo(PtoState.INIT_STEP, 1, 2, initTime);

        stopWatch.start();
        List<byte[]> randomOracleKeyPayload = new LinkedList<>();
        byte[] randomOracleKey = BlockUtils.zeroBlock();
        secureRandom.nextBytes(randomOracleKey);
        randomOracleKeyPayload.add(randomOracleKey);
        DataPacketHeader randomOracleKeyHeader = new DataPacketHeader(
            encodeTaskId, getPtoDesc().getPtoId(), PtoStep.RECEIVER_SEND_RANDOM_ORACLE_KEY.ordinal(), extraInfo,
            ownParty().getPartyId(), otherParty().getPartyId()
        );
        rpc.send(DataPacket.fromByteArrayList(randomOracleKeyHeader, randomOracleKeyPayload));
        randomOracle = PrfFactory.createInstance(envType, CommonConstants.BLOCK_BYTE_LENGTH);
        randomOracle.setKey(randomOracleKey);
        stopWatch.stop();
        long keyTime = stopWatch.getTime(TimeUnit.MILLISECONDS);
        stopWatch.reset();
        logStepInfo(PtoState.INIT_STEP, 2, 2, keyTime);

        logPhaseInfo(PtoState.INIT_END);
    }

    @Override
    public BspCotReceiverOutput receive(int[] alphaArray, int eachNum) throws MpcAbortException {
        setPtoInput(alphaArray, eachNum);
        return receive();
    }

    @Override
    public BspCotReceiverOutput receive(int[] alphaArray, int eachNum, CotReceiverOutput preReceiverOutput)
        throws MpcAbortException {
        setPtoInput(alphaArray, eachNum, preReceiverOutput);
        cotReceiverOutput = preReceiverOutput;
        return receive();
    }

    private BspCotReceiverOutput receive() throws MpcAbortException {
        logPhaseInfo(PtoState.PTO_BEGIN);

        stopWatch.start();
        // R send (extend, h) to F_COT, which returns (r_i, t_i) ∈ {0,1} × {0,1}^κ to R
        if (cotReceiverOutput == null) {
            boolean[] rs = BinaryUtils.randomBinary(cotNum, secureRandom);
            cotReceiverOutput = coreCotReceiver.receive(rs);
        } else {
            cotReceiverOutput.reduce(cotNum);
        }
        int dpprfCotNum = BpRdpprfFactory.getPrecomputeNum(bpRdpprfConfig, batchNum, eachNum);
        CotReceiverOutput extendCotReceiverOutput = cotReceiverOutput.split(dpprfCotNum);
        checkCotReceiverOutput = cotReceiverOutput.split(CommonConstants.BLOCK_BIT_LENGTH);
        cotReceiverOutput = null;
        stopWatch.stop();
        long cotTime = stopWatch.getTime(TimeUnit.MILLISECONDS);
        stopWatch.reset();
        logStepInfo(PtoState.PTO_STEP, 1, 4, cotTime);

        stopWatch.start();
        bpRdpprfReceiverOutput = bpRdpprfReceiver.puncture(alphaArray, eachNum, extendCotReceiverOutput);
        stopWatch.stop();
        long dpprfTime = stopWatch.getTime(TimeUnit.MILLISECONDS);
        stopWatch.reset();
        logStepInfo(PtoState.PTO_STEP, 2, 4, dpprfTime);

        stopWatch.start();
        DataPacketHeader correlateHeader = new DataPacketHeader(
            encodeTaskId, getPtoDesc().getPtoId(), PtoStep.SENDER_SEND_CORRELATE.ordinal(), extraInfo,
            otherParty().getPartyId(), ownParty().getPartyId()
        );
        List<byte[]> correlatePayload = rpc.receive(correlateHeader).getPayload();
        BspCotReceiverOutput receiverOutput = generateReceiverOutput(correlatePayload);
        bpRdpprfReceiverOutput = null;
        stopWatch.stop();
        long outputTime = stopWatch.getTime(TimeUnit.MILLISECONDS);
        stopWatch.reset();
        logStepInfo(PtoState.PTO_STEP, 3, 4, outputTime);

        stopWatch.start();
        List<byte[]> checkChoicePayload = generateCheckChoicePayload();
        DataPacketHeader checkChoiceHeader = new DataPacketHeader(
            encodeTaskId, getPtoDesc().getPtoId(), PtoStep.RECEIVER_SEND_CHECK_CHOICES.ordinal(), extraInfo,
            ownParty().getPartyId(), otherParty().getPartyId()
        );
        rpc.send(DataPacket.fromByteArrayList(checkChoiceHeader, checkChoicePayload));
        // locally compute H'(w), then receive H'(v)
        byte[] expectHashValue = computeExpectHashValue(receiverOutput);
        DataPacketHeader actualHashValueHeader = new DataPacketHeader(
            encodeTaskId, getPtoDesc().getPtoId(), PtoStep.SENDER_SEND_HASH_VALUE.ordinal(), extraInfo,
            otherParty().getPartyId(), ownParty().getPartyId()
        );
        List<byte[]> actualHashValuePayload = rpc.receive(actualHashValueHeader).getPayload();
        MpcAbortPreconditions.checkArgument(actualHashValuePayload.size() == 1);
        byte[] actualHashValue = actualHashValuePayload.remove(0);
        MpcAbortPreconditions.checkArgument(Arrays.equals(expectHashValue, actualHashValue));
        stopWatch.stop();
        long checkTime = stopWatch.getTime(TimeUnit.MILLISECONDS);
        stopWatch.reset();
        logStepInfo(PtoState.PTO_STEP, 4, 4, checkTime);

        logPhaseInfo(PtoState.PTO_END);
        return receiverOutput;
    }

    private BspCotReceiverOutput generateReceiverOutput(List<byte[]> correlatePayload) throws MpcAbortException {
        MpcAbortPreconditions.checkArgument(correlatePayload.size() == batchNum);
        byte[][] correlateByteArrays = correlatePayload.toArray(byte[][]::new);
        IntStream batchIndexIntStream = IntStream.range(0, batchNum);
        batchIndexIntStream = parallel ? batchIndexIntStream.parallel() : batchIndexIntStream;
        SspCotReceiverOutput[] sspCotReceiverOutputs = batchIndexIntStream
            .mapToObj(batchIndex -> {
                byte[][] rbArray = bpRdpprfReceiverOutput.get(batchIndex).getV1Array();
                // computes w[α]
                for (int i = 0; i < eachNum; i++) {
                    if (i != alphaArray[batchIndex]) {
                        BlockUtils.xori(correlateByteArrays[batchIndex], rbArray[i]);
                    }
                }
                rbArray[alphaArray[batchIndex]] = correlateByteArrays[batchIndex];
                return SspCotReceiverOutput.create(alphaArray[batchIndex], rbArray);
            })
            .toArray(SspCotReceiverOutput[]::new);
        return new BspCotReceiverOutput(sspCotReceiverOutputs);
    }

    private List<byte[]> generateCheckChoicePayload() {
        // R computes ϕ := Σ_{i ∈ [m]} χ_{α_l}^l ∈ F_{2^κ}
        byte[] phi = BlockUtils.zeroBlock();
        for (int l = 0; l < batchNum; l++) {
            byte[] indexMessage = ByteBuffer.allocate(Long.BYTES + Integer.BYTES + Integer.BYTES)
                .putLong(extraInfo).putInt(l).putInt(alphaArray[l]).array();
            // Sample χ_α
            byte[] chiAlpha = randomOracle.getBytes(indexMessage);
            BlockUtils.xori(phi, chiAlpha);
        }
        // R sends x' := x + x^* ∈ F_2^κ to S
        byte[] xStar = BinaryUtils.binaryToByteArray(checkCotReceiverOutput.getChoices());
        BlockUtils.xori(phi, xStar);
        List<byte[]> checkChoicePayload = new LinkedList<>();
        checkChoicePayload.add(phi);
        return checkChoicePayload;
    }

    private byte[] computeExpectHashValue(BspCotReceiverOutput receiverOutput) {
        // R computes Z :=  Σ_{i ∈ [κ]} (z^*[i]·X^i) ∈ F_{2^κ}
        byte[] z = BlockUtils.zeroBlock();
        for (int checkIndex = 0; checkIndex < CommonConstants.BLOCK_BIT_LENGTH; checkIndex++) {
            byte[] zi = checkCotReceiverOutput.getRb(checkIndex);
            // z^*[i]·X^i
            byte[] xi = BlockUtils.zeroBlock();
            BinaryUtils.setBoolean(xi, checkIndex, true);
            gf2k.muli(zi, xi);
            // z += z^*[i]·X^i
            gf2k.addi(z, zi);
        }
        checkCotReceiverOutput = null;
        // R computes W := Σ_{l ∈ [m]}(Σ_{i ∈ [n]} (χ[i]·w[i])) + Z ∈ F_{2^κ}
        IntStream lIntStream = IntStream.range(0, batchNum);
        lIntStream = parallel ? lIntStream.parallel() : lIntStream;
        byte[][] ws = lIntStream
            .mapToObj(l -> {
                byte[] w = BlockUtils.zeroBlock();
                for (int i = 0; i < eachNum; i++) {
                    // samples uniform {χ_i}_{i ∈ [n]}
                    byte[] indexMessage = ByteBuffer.allocate(Long.BYTES + Integer.BYTES + Integer.BYTES)
                        .putLong(extraInfo).putInt(l).putInt(i).array();
                    byte[] chi = randomOracle.getBytes(indexMessage);
                    // χ[i]·w[i]
                    gf2k.muli(chi, receiverOutput.get(l).getRb(i));
                    // w += χ[i]·w[i]
                    BlockUtils.xori(w, chi);
                }
                return w;
            })
            .toArray(byte[][]::new);
        // W := Σ_{i ∈ [n]} (χ[i]·w[i]) + Z
        byte[] w = BlockUtils.zeroBlock();
        for (int l = 0; l < batchNum; l++) {
            gf2k.addi(w, ws[l]);
        }
        gf2k.addi(w, z);
        // H'(w)
        return hash.digestToBytes(w);
    }
}
