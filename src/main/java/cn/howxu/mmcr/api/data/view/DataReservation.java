package cn.howxu.mmcr.api.data.view;

/**
 * Public transactional reservation for a data repository request.
 *
 * @author howxu <dev@howxu.cn>
 */
public interface DataReservation {
    boolean commit(DataStorage.Transaction transaction);

    void cancel();
}
