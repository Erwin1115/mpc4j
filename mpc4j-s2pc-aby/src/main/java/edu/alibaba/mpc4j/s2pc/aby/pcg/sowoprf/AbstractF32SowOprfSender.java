package edu.alibaba.mpc4j.s2pc.aby.pcg.sowoprf;

import edu.alibaba.mpc4j.common.rpc.MpcAbortException;
import edu.alibaba.mpc4j.common.rpc.Party;
import edu.alibaba.mpc4j.common.rpc.Rpc;
import edu.alibaba.mpc4j.common.rpc.desc.PtoDesc;
import edu.alibaba.mpc4j.common.rpc.pto.AbstractTwoPartyPto;
import edu.alibaba.mpc4j.common.tool.MathPreconditions;
import edu.alibaba.mpc4j.common.tool.bitmatrix.dense.DenseBitMatrix;
import edu.alibaba.mpc4j.common.tool.galoisfield.Z3ByteField;
import edu.alibaba.mpc4j.common.tool.utils.BlockUtils;

import java.util.Arrays;

/**
 * abstract (F3, F2)-sowOPRF sender.
 *
 * @author Weiran Liu
 * @date 2024/6/6
 */
public abstract class AbstractF32SowOprfSender extends AbstractTwoPartyPto implements F32SowOprfSender {
    /**
     * Z3 field
     */
    protected final Z3ByteField z3Field;
    /**
     * (F3, F2)-wPRF
     */
    private final F32Wprf f32Wprf;
    /**
     * key
     */
    protected final byte[] key;
    /**
     * matrix A
     */
    protected F32WprfMatrix matrixA;
    /**
     * matrix B
     */
    protected DenseBitMatrix matrixB;
    /**
     * expect batch size
     */
    protected int expectBatchSize;
    /**
     * batch size
     */
    protected int batchSize;

    protected AbstractF32SowOprfSender(PtoDesc ptoDesc, Rpc senderRpc, Party receiverParty, F32SowOprfConfig config) {
        super(ptoDesc, senderRpc, receiverParty, config);
        z3Field = new Z3ByteField();
        byte[] seedA = BlockUtils.zeroBlock();
        byte[] seedB = BlockUtils.zeroBlock();
        Arrays.fill(seedB, (byte) 0xFF);
        f32Wprf = new F32Wprf(z3Field, seedA, seedB, config.getMatrixType());
        matrixA = f32Wprf.getMatrixA();
        matrixB = f32Wprf.getMatrixB();
        key = f32Wprf.keyGen(secureRandom);
        f32Wprf.init(key);
    }

    protected void setInitInput(int expectBatchSize) {
        MathPreconditions.checkPositive("expectBatchSize", expectBatchSize);
        this.expectBatchSize = expectBatchSize;
        initState();
    }

    protected void setInitInput() {
        expectBatchSize = -1;
        initState();
    }

    protected void setPtoInput(int batchSize) throws MpcAbortException {
        checkInitialized();
        MathPreconditions.checkPositive("batchSize", batchSize);
        this.batchSize = batchSize;
    }

    @Override
    public byte[] prf(byte[] x) {
        return f32Wprf.prf(x);
    }
}
