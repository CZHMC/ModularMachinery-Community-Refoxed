package cn.howxu.mmcr.compat.jei;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.machine.MachineRegistry;
import cn.howxu.mmcr.api.machine.MachineDefinitions;
import cn.howxu.mmcr.api.publicapi.event.MMCRJeiRecipeInformationEvent;
import cn.howxu.mmcr.client.gui.BlueprintScreen;
import cn.howxu.mmcr.internal.client.RecipeInformationRegistry;
import cn.howxu.mmcr.registry.ModBlocks;
import java.util.LinkedHashSet;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JEI plugin entrypoint for MMCR.
 *
 * @author howxu <dev@howxu.cn>
 */
@mezz.jei.api.JeiPlugin
public final class JeiPlugin implements IModPlugin {

    @Override
    public Identifier getPluginUid() {
        return MMCR.id("jei");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        MMCRJeiRecipeInformationEvent event = new MMCRJeiRecipeInformationEvent();
        NeoForge.EVENT_BUS.post(event);
        event.freeze();
        RecipeInformationRegistry.replacePublic(event.entries());
        JeiIngredientAdapterRegistry.registerBuiltIns();
        var guiHelper = registration.getJeiHelpers().getGuiHelper();
        registration.addRecipeCategories(new MachineStructureCategory(guiHelper));
        Map<Identifier, List<Identifier>> machinesByPool = machineIdsByPool();
        JeiRuntimeReloader.markRegisteredRecipePoolCategories(machinesByPool.keySet());
        machinesByPool.forEach((poolId, machineIds) -> registration.addRecipeCategories(
                new MachineRecipeCategory(guiHelper, poolId, machineIds.getFirst())));
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        JeiRuntimeReloader.setRuntime(runtime);
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addRecipes(JeiMachineRecipeTypes.STRUCTURE, MachineRegistry.getAll().values().stream()
                .map(MachineStructureDisplay::from)
                .toList());
        var displaysByPool = MachineRecipeDisplays.byPool();
        Set<Identifier> poolIds = machineIdsByPool().keySet();
        Map<Identifier, List<MachineRecipeDisplay>> registeredDisplays = displaysByPool.entrySet().stream()
                .filter(entry -> poolIds.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (first, ignored) -> first, LinkedHashMap::new));
        JeiRuntimeReloader.captureInitialDisplays(registeredDisplays);
        displaysByPool.forEach((poolId, displays) -> {
            if (!poolIds.contains(poolId)) {
                displays.forEach(display -> MMCR.LOG.warn("Skipping JEI recipe {} for unknown recipe pool {}", display.recipeId(), poolId));
            }
        });
        poolIds.forEach(poolId -> registration.addRecipes(
                JeiMachineRecipeTypes.forPool(poolId),
                displaysByPool.getOrDefault(poolId, List.of())));
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        machineIdsByPool().forEach((poolId, machineIds) -> machineIds.forEach(machineId -> {
            ItemStack controller = new ItemStack(ModBlocks.controllerFor(machineId).get());
            registration.addCraftingStation(JeiMachineRecipeTypes.forPool(poolId), controller);
        }));
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        var helper = registration.getTransferHelper();
        machineIdsByPool().keySet().forEach(poolId -> {
            var type = JeiMachineRecipeTypes.forPool(poolId);
            registration.addRecipeTransferHandler(new MachineRecipeTransferHandler(helper, type), type);
        });
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiScreenHandler(BlueprintScreen.class, new BlueprintScreenJeiHandler());
    }

    static Set<Identifier> machineIds() {
        Set<Identifier> ids = new LinkedHashSet<>(MachineRegistry.getAll().keySet());
        ids.addAll(MachineDefinitions.effectiveSnapshot().keySet());
        return ids;
    }

    static Map<Identifier, List<Identifier>> machineIdsByPool() {
        return machineIds().stream()
                .sorted()
                .collect(Collectors.groupingBy(MachineRegistry::recipePoolForMachine,
                        java.util.LinkedHashMap::new, Collectors.toList()));
    }

}
