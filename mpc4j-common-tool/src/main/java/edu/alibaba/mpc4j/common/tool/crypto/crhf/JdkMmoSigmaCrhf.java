package edu.alibaba.mpc4j.common.tool.crypto.crhf;

import edu.alibaba.mpc4j.common.tool.EnvType;
import edu.alibaba.mpc4j.common.tool.crypto.prp.Prp;
import edu.alibaba.mpc4j.common.tool.crypto.prp.PrpFactory;
import edu.alibaba.mpc4j.common.tool.utils.BlockUtils;

/**
 * MMO_σ(x) = π(σ(x)) ⊕ σ(x)（满足电路抗关联性），由下述论文第7.3节给出：
 * <p>
 * Guo C, Katz J, Wang X, et al. Efficient and secure multiparty computation from fixed-key block ciphers.
 * 2020 IEEE Symposium on Security and Privacy (SP). IEEE, 2020: 825-841.
 * </p>
 * 论文中指出，可以使用_mm_shuffle_epi32(a, 78) ^ (a & makeBlock(0xFFFFFFFFFFFFFFFF, 0x00)来实现，_mm_shuffle_epi32(a, 78)的
 * 含义是（注意78 = 0x01 0x00 0x11 0x10，对应2032）当x = [a_0, a_1, a_2, a_3]，每个为32比特时，s(x) = [a_1, a_0, a_3, a_2]。
 * 但这一步速度过快，因此我们使用Java的方法直接实现。
 *
 * @author Weiran Liu
 * @date 2022/01/11
 */
class JdkMmoSigmaCrhf implements Crhf {
    /**
     * mask = 1^{64} || 0^{64}，参见论文第8.1节
     */
    private static final byte[] MASK = new byte[]{
        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
        (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    };
    /**
     * 伪随机置换π
     */
    private final Prp prp;

    /**
     * 构建MMO_σ(x)。
     *
     * @param envType 环境类型。
     */
    JdkMmoSigmaCrhf(EnvType envType) {
        prp = PrpFactory.createInstance(envType);
        // 默认伪随机置换密钥为全0
        prp.setKey(BlockUtils.zeroBlock());
    }

    @Override
    public byte[] hash(byte[] block) {
        // σ(x)
        byte[] sigmaX = sigma(block);
        // π(σ(x))
        byte[] output = prp.prp(sigmaX);
        // π(σ(x)) ⊕ σ(x)
        BlockUtils.xori(output, sigmaX);
        return output;
    }

    @Override
    public CrhfFactory.CrhfType getCrhfType() {
        return CrhfFactory.CrhfType.JDK_MMO_SIGMA;
    }

    /**
     * 实现σ(x) = mm_shuffle_epi32(a, 78) ⊕ and_si128(a, mask)。
     *
     * @param x 输入的x。
     * @return σ(x)。
     */
    private byte[] sigma(byte[] x) {
        assert BlockUtils.valid(x);
        byte[] sigmaX = BlockUtils.zeroBlock();
        // mm_shuffle_epi32(a, 78)，即当x = [a_0, a_1, a_2, a_3]，每个为32比特时，s(x) = [a_1, a_0, a_3, a_2]
        System.arraycopy(x, Integer.BYTES, sigmaX, 0, Integer.BYTES);
        System.arraycopy(x, 0, sigmaX, Integer.BYTES, Integer.BYTES);
        System.arraycopy(x, Integer.BYTES * 3, sigmaX, Integer.BYTES * 2, Integer.BYTES);
        System.arraycopy(x, Integer.BYTES * 2, sigmaX, Integer.BYTES * 3, Integer.BYTES);
        // and_si128(a, mask)
        byte[] maskX = BlockUtils.and(x, MASK);
        // σ(x)
        BlockUtils.xori(sigmaX, maskX);

        return sigmaX;
    }
}
