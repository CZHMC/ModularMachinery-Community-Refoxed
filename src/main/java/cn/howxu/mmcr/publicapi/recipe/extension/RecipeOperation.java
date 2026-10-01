package cn.howxu.mmcr.publicapi.recipe.extension;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
/** Open transactional operation SPI. Resource mutations must participate in the supplied transaction.
 * Prepared lambdas reject rescaling by default. Override forParallelism to explicitly support it,
 * or use PlanningView.deferred to construct an operation at the final parallelism.
 * @author howxu <dev@howxu.cn> */
@FunctionalInterface
public interface RecipeOperation {
    OperationResult commit(TransactionContext transaction);
    default RecipeOperation forParallelism(long parallelism) { return null; }
}
