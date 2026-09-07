package cn.howxu.mmcr.api.publicapi.network;

import cn.howxu.mmcr.api.publicapi.data.DataStorage;

/** Handles a public machine network request on the server thread.
 * @author howxu <dev@howxu.cn>
 */
@FunctionalInterface
public interface RequestProcess {
    void process(RequestBody body, RequestInfo request, DataStorage senderStorage, DataStorage receiverStorage);
}
