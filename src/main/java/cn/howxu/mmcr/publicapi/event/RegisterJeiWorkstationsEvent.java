package cn.howxu.mmcr.publicapi.event;

import cn.howxu.mmcr.internal.api.facade.client.ClientRegistrationAdapters;
import cn.howxu.mmcr.publicapi.client.jei.Workstation;
import cn.howxu.mmcr.publicapi.client.jei.WorkstationRegistrar;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.neoforged.bus.api.Event;

/** Client event posted on NeoForge.EVENT_BUS to add category workstations.
 * @author howxu <dev@howxu.cn>
 */
public final class RegisterJeiWorkstationsEvent extends Event {
    private final WorkstationRegistrar registrar;
    public RegisterJeiWorkstationsEvent() { registrar = ClientRegistrationAdapters.workstations(); }
    public WorkstationRegistrar registrar() { return registrar; }
    public void addRecipePoolWorkstation(Identifier pool, Identifier item) { registrar.addRecipePoolWorkstation(pool, item); }
    public void addRecipePoolWorkstation(Identifier pool, ItemLike item) { registrar.addRecipePoolWorkstation(pool, item); }
    public void addRecipePoolWorkstation(Identifier pool, ItemStack item) { registrar.addRecipePoolWorkstation(pool, item); }
    public void addMachineWorkstation(Identifier machine, Identifier type) { registrar.addMachineWorkstation(machine, type); }
    public List<Workstation> entries() { return registrar.entries(); }
}
