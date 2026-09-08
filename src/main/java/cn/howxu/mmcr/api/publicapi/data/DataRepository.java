package cn.howxu.mmcr.api.publicapi.data;

import net.minecraft.resources.Identifier;

/**
 * Public extension point for future lazy data repositories.
 *
 * @author howxu <dev@howxu.cn>
 */
public interface DataRepository {
    Identifier id();

    DataRepositoryRequest request(DataRepositoryContext context);
}
