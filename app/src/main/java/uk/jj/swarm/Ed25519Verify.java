package uk.jj.swarm;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;

/** Minimal Ed25519 signature check (RFC 8032), no libraries needed. Used to check the TorusApps catalogue seal. */
final class Ed25519Verify {
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger I = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    private static final BigInteger[] B;
    static {
        BigInteger by = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
        BigInteger bx = xRecover(by);
        if (bx.testBit(0)) bx = P.subtract(bx);
        B = new BigInteger[]{bx, by, BigInteger.ONE, bx.multiply(by).mod(P)};
    }

    private Ed25519Verify() {}

    private static BigInteger xRecover(BigInteger y) {
        BigInteger y2 = y.multiply(y);
        BigInteger xx = y2.subtract(BigInteger.ONE).multiply(D.multiply(y2).add(BigInteger.ONE).modInverse(P)).mod(P);
        if (xx.signum() == 0) return BigInteger.ZERO;
        BigInteger x = xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (!x.multiply(x).subtract(xx).mod(P).equals(BigInteger.ZERO)) x = x.multiply(I).mod(P);
        if (!x.multiply(x).subtract(xx).mod(P).equals(BigInteger.ZERO)) return null;
        return x;
    }

    private static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
        BigInteger a = p[1].subtract(p[0]).multiply(q[1].subtract(q[0])).mod(P);
        BigInteger b = p[1].add(p[0]).multiply(q[1].add(q[0])).mod(P);
        BigInteger c = p[3].multiply(BigInteger.valueOf(2)).multiply(D).multiply(q[3]).mod(P);
        BigInteger dd = p[2].multiply(BigInteger.valueOf(2)).multiply(q[2]).mod(P);
        BigInteger e = b.subtract(a), f = dd.subtract(c), g = dd.add(c), h = b.add(a);
        return new BigInteger[]{e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P)};
    }

    private static BigInteger[] mul(BigInteger s, BigInteger[] pt) {
        BigInteger[] q = {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        for (int i = s.bitLength() - 1; i >= 0; i--) {
            q = add(q, q);
            if (s.testBit(i)) q = add(q, pt);
        }
        return q;
    }

    private static boolean same(BigInteger[] p, BigInteger[] q) {
        return p[0].multiply(q[2]).subtract(q[0].multiply(p[2])).mod(P).signum() == 0
            && p[1].multiply(q[2]).subtract(q[1].multiply(p[2])).mod(P).signum() == 0;
    }

    private static BigInteger le(byte[] b, int off, int len) {
        byte[] r = new byte[len];
        for (int i = 0; i < len; i++) r[i] = b[off + len - 1 - i];
        return new BigInteger(1, r);
    }

    private static BigInteger[] decode(byte[] s, int off) {
        byte[] c = Arrays.copyOfRange(s, off, off + 32);
        boolean sign = (c[31] & 0x80) != 0;
        c[31] &= 0x7f;
        BigInteger y = le(c, 0, 32);
        if (y.compareTo(P) >= 0) return null;
        BigInteger x = xRecover(y);
        if (x == null) return null;
        if (x.testBit(0) != sign) {
            if (x.signum() == 0) return null;
            x = P.subtract(x);
        }
        return new BigInteger[]{x, y, BigInteger.ONE, x.multiply(y).mod(P)};
    }

    static boolean verify(byte[] pub, byte[] msg, byte[] sig) {
        try {
            if (pub.length != 32 || sig.length != 64) return false;
            BigInteger[] a = decode(pub, 0);
            BigInteger[] r = decode(sig, 0);
            if (a == null || r == null) return false;
            BigInteger s = le(sig, 32, 32);
            if (s.compareTo(L) >= 0) return false;
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            md.update(sig, 0, 32);
            md.update(pub);
            md.update(msg);
            BigInteger h = le(md.digest(), 0, 64).mod(L);
            return same(mul(s, B), add(r, mul(h, a)));
        } catch (Exception e) {
            return false;
        }
    }
}
