package cn.howxu.mmcr.compat.mekanism.loaded;

/** Fixed storage sizes used by the loaded Mekanism port families.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class MekanismPortSizes {
    public static final long CHEMICAL_BASIC_CAPACITY = 64_000L;
    public static final long CHEMICAL_ADVANCED_CAPACITY = 256_000L;
    public static final long CHEMICAL_ELITE_CAPACITY = 1_024_000L;
    public static final long CHEMICAL_ULTIMATE_CAPACITY = 8_192_000L;
    public static final long RADIOACTIVE_CHEMICAL_CAPACITY = 512_000L;
    public static final double HEAT_CAPACITY = 300D;

    public enum ChemicalTier {
        BASIC("basic", CHEMICAL_BASIC_CAPACITY),
        ADVANCED("advanced", CHEMICAL_ADVANCED_CAPACITY),
        ELITE("elite", CHEMICAL_ELITE_CAPACITY),
        ULTIMATE("ultimate", CHEMICAL_ULTIMATE_CAPACITY);

        private final String id;
        private final long capacity;

        ChemicalTier(String id, long capacity) {
            this.id = id;
            this.capacity = capacity;
        }

        public String id() {
            return id;
        }

        public long capacity() {
            return capacity;
        }
    }

    private MekanismPortSizes() {
    }
}
