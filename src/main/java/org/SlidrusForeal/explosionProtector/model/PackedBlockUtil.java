package org.SlidrusForeal.explosionProtector.model;

import org.bukkit.block.Block;

public final class PackedBlockUtil {
    public static final int MIN_XZ = -33_554_432;
    public static final int MAX_XZ = 33_554_431;
    public static final int MIN_Y = -2_048;
    public static final int MAX_Y = 2_047;

    private PackedBlockUtil() {
    }

    public static long pack(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38)
                | (((long) z & 0x3FFFFFFL) << 12)
                | ((long) y & 0xFFFL);
    }

    public static Long tryPack(int x, int y, int z) {
        if (x < MIN_XZ || x > MAX_XZ || z < MIN_XZ || z > MAX_XZ || y < MIN_Y || y > MAX_Y) {
            return null;
        }
        return pack(x, y, z);
    }

    public static Long tryPack(Block block) {
        return tryPack(block.getX(), block.getY(), block.getZ());
    }

    public static int unpackX(long packed) {
        int x = (int) (packed >> 38);
        if (x >= 0x2000000) {
            x -= 0x4000000;
        }
        return x;
    }

    public static int unpackY(long packed) {
        int y = (int) (packed & 0xFFFL);
        if (y >= 0x800) {
            y -= 0x1000;
        }
        return y;
    }

    public static int unpackZ(long packed) {
        int z = (int) ((packed >> 12) & 0x3FFFFFFL);
        if (z >= 0x2000000) {
            z -= 0x4000000;
        }
        return z;
    }

    public static long packChunkFromPacked(long packed) {
        return packChunk(unpackX(packed) >> 4, unpackZ(packed) >> 4);
    }

    public static long packChunk(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
