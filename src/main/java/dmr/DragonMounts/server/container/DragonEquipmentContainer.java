package dmr.DragonMounts.server.container;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A size-3 delegating VIEW over indices 0-2 (saddle/armor/chest) of the dragon's real
 * backing inventory container — every read/write passes straight through to that
 * backing container at the SAME index; this class holds no state of its own.
 *
 * <p>
 * Client-side inventory sorter mods (ClientSort, and the whole MouseWheelie sorter
 * lineage) group a menu's slots into contiguous "sort regions" purely by
 * backing-{@code Container} object identity plus ascending slot index —
 * {@code slot.container != lastContainer} starts a new region. {@link
 * DragonContainerMenu} used to back ALL 30 of its slots (saddle, armor, chest, and the
 * 27-slot storage grid) with the SAME {@code SimpleContainer} instance (the dragon's
 * actual inventory, {@code DragonInventoryHandler.DragonInventory#inventory} —
 * {@code DragonInventoryHandler.java:157-161}, index scheme 0=saddle 1=armor 2=chest
 * 3-29=storage), so a sorter saw one 30-slot region: a filled saddle/armor slot passes
 * the default {@code mayPickup} (true) and the storage grid's slots have no {@code
 * mayPlace} restriction, so the sorter happily swept equipment down into the grid.
 * Vanilla's own {@code HorseInventoryMenu} has the identical hazard for its saddle slot
 * (same one-Container-for-everything shape) — ClientSort ships a default policy that
 * doesn't special-case it, so the durable fix has to be on our side, structurally.
 *
 * <p>
 * This view gives the three equipment slots a DIFFERENT {@code Container} identity so
 * they form their own one-region sort boundary, split cleanly from the storage grid —
 * while every read/write still flows straight through to the SAME backing container at
 * the SAME indices (0/1/2). Because of that, NBT format ({@code
 * DragonInventoryHandler.DragonInventory#writeNBT}/{@code readNBT}, a flat byte
 * {@code Slot} index — {@code DragonInventoryHandler.java:164-195}), sync packets, the
 * global {@code DragonWorldData} store, and every other piece of code that indexes the
 * dragon's inventory 0-29 directly are all completely unaffected: this is purely a
 * menu-construction-time detail, invisible to everything except a {@code Slot}'s
 * {@code container} field identity (which is exactly, and only, what a sorter inspects).
 *
 * <p>
 * {@link DragonContainerMenu#stillValid} and {@code
 * DragonInventoryComponent#hasInventoryChanged} deliberately keep comparing/calling
 * against the REAL backing container (the menu's own {@code dragonContainer} field),
 * never this view — see their call sites.
 */
class DragonEquipmentContainer implements Container {

    static final int SIZE = 3;

    private final Container backing;

    DragonEquipmentContainer(Container backing) {
        this.backing = backing;
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < SIZE; i++) {
            if (!backing.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int index) {
        return backing.getItem(index);
    }

    @Override
    public ItemStack removeItem(int index, int count) {
        return backing.removeItem(index, count);
    }

    @Override
    public ItemStack removeItemNoUpdate(int index) {
        return backing.removeItemNoUpdate(index);
    }

    @Override
    public void setItem(int index, ItemStack stack) {
        backing.setItem(index, stack);
    }

    @Override
    public void setChanged() {
        // Delegates to the SAME backing container the DragonInventory's
        // ContainerListener is registered on, so dirty-tracking/sync keep working
        // exactly as before — this view adds no state, and therefore no separate
        // "changed" bookkeeping, to keep in sync.
        backing.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return backing.stillValid(player);
    }

    @Override
    public void clearContent() {
        // Only OUR three indices — backing.clearContent() would wipe the whole
        // 30-slot inventory, not just the equipment view's slice of it.
        for (int i = 0; i < SIZE; i++) {
            backing.setItem(i, ItemStack.EMPTY);
        }
    }
}
