package mezz.jei.common.transfer;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class BasicRecipeTransferHandlerServer {
	private static final Logger LOGGER = LogManager.getLogger();

	private BasicRecipeTransferHandlerServer() {
	}

	/**
	 * Called server-side to actually put the items in place.
	 */
	public static void setItems(
		Player player,
		List<TransferOperation> transferOperations,
		List<Slot> craftingSlots,
		List<Slot> inventorySlots,
		boolean maxTransfer,
		boolean requireCompleteSets,
		List<Integer> itemTransferAmounts) {
		if (!RecipeTransferUtil.validateSlots(player, transferOperations, craftingSlots, inventorySlots)) {
			return;
		}

		Map<Slot, ItemStackWithSlotHint> recipeSlotToRequiredItemStack = calculateRequiredStacks(transferOperations, player);
		if (recipeSlotToRequiredItemStack == null) {
			return;
		}

		// Transfer as many items as possible only if it has been explicitly requested by the implementation
		// and a max-transfer operation has been requested by the player.
		boolean transferAsCompleteSets = requireCompleteSets || !maxTransfer;

		Map<Slot, ItemStack> recipeSlotToTakenStacks = takeItemsFromInventory(
			player,
			recipeSlotToRequiredItemStack,
			craftingSlots,
			inventorySlots,
			transferAsCompleteSets,
			maxTransfer,
				itemTransferAmounts
		);

		if (recipeSlotToTakenStacks.isEmpty()) {
			LOGGER.error("Tried to transfer recipe but was unable to remove any items from the inventory.");
			return;
		}

		// clear the crafting grid
		List<ItemStack> clearedCraftingItems = clearCraftingGrid(craftingSlots, player);

		// put items into the crafting grid
		List<ItemStack> remainderItems = putItemsIntoCraftingGrid(recipeSlotToTakenStacks, requireCompleteSets);

		// put leftover items back into the inventory
		stowItems(player, inventorySlots, clearedCraftingItems);
		stowItems(player, inventorySlots, remainderItems);

		AbstractContainerMenu container = player.containerMenu;
		container.broadcastChanges();
	}

	private static int getSlotStackLimit(
		Map<Slot, ItemStack> recipeSlotToTakenStacks,
		boolean requireCompleteSets
	) {
		if (!requireCompleteSets) {
			return Integer.MAX_VALUE;
		}

		return recipeSlotToTakenStacks.entrySet().stream()
			.mapToInt(e -> {
				Slot craftingSlot = e.getKey();
				ItemStack transferItem = e.getValue();
				if (craftingSlot.mayPlace(transferItem)) {
					return craftingSlot.getMaxStackSize(transferItem);
				}
				return Integer.MAX_VALUE;
			})
			.min()
			.orElse(Integer.MAX_VALUE);
	}

	private static List<ItemStack> clearCraftingGrid(List<Slot> craftingSlots, Player player) {
		List<ItemStack> clearedCraftingItems = new ArrayList<>();
		for (Slot craftingSlot : craftingSlots) {
			if (!craftingSlot.mayPickup(player)) {
				continue;
			}

			ItemStack item = craftingSlot.getItem();
			if (!item.isEmpty() && craftingSlot.mayPlace(item)) {
				ItemStack craftingItem = craftingSlot.safeTake(Integer.MAX_VALUE, Integer.MAX_VALUE, player);
				clearedCraftingItems.add(craftingItem);
			}
		}
		return clearedCraftingItems;
	}

	private static List<ItemStack> putItemsIntoCraftingGrid(
		Map<Slot, ItemStack> recipeSlotToTakenStacks,
		boolean requireCompleteSets
	) {
		final int slotStackLimit = getSlotStackLimit(recipeSlotToTakenStacks, requireCompleteSets);
		List<ItemStack> remainderItems = new ArrayList<>();

		recipeSlotToTakenStacks.forEach((slot, stack) -> {
			ItemStack remainder = slot.safeInsert(stack, slotStackLimit);
			if (!remainder.isEmpty()) {
				remainderItems.add(remainder);
			}
		});

		return remainderItems;
	}

	@Nullable
	private static Map<Slot, ItemStackWithSlotHint> calculateRequiredStacks(List<TransferOperation> transferOperations, Player player) {
		Map<Slot, ItemStackWithSlotHint> recipeSlotToRequired = new LinkedHashMap<>(transferOperations.size());
		for (TransferOperation transferOperation : transferOperations) {
			Slot recipeSlot = transferOperation.craftingSlot(player.containerMenu);
			Slot inventorySlot = transferOperation.inventorySlot(player.containerMenu);
			if (!inventorySlot.allowModification(player)) {
				LOGGER.error(
					"Tried to transfer recipe but was given an" +
					" inventory slot that the player can't pickup from: {}" ,
					inventorySlot.index
				);
				return null;
			}
			final ItemStack slotStack = inventorySlot.getItem();
			if (slotStack.isEmpty()) {
				LOGGER.error(
					"Tried to transfer recipe but was given an" +
					" empty inventory slot as an ingredient source: {}",
					inventorySlot.index
				);
				return null;
			}
			ItemStack stack = slotStack.copy();
			stack.setCount(1);
			recipeSlotToRequired.put(recipeSlot, new ItemStackWithSlotHint(inventorySlot, stack));
		}
		return recipeSlotToRequired;
	}

	@Nonnull
	private static Map<Slot, ItemStack> takeItemsFromInventory(
		Player player,
		Map<Slot, ItemStackWithSlotHint> recipeSlotToRequiredItemStack,
		List<Slot> craftingSlots,
		List<Slot> inventorySlots,
		boolean transferAsCompleteSets,
		boolean maxTransfer,
		List<Integer> itemTransferAmount) {
		if (!maxTransfer) {
			return removeOneSetOfItemsFromInventory(
				player,
				recipeSlotToRequiredItemStack,
				craftingSlots,
				inventorySlots,
				transferAsCompleteSets,
					itemTransferAmount
			);
		}

		final Map<Slot, ItemStack> recipeSlotToResult = new LinkedHashMap<>(recipeSlotToRequiredItemStack.size());
		while (true) {
			final Map<Slot, ItemStack> foundItemsInSet = removeOneSetOfItemsFromInventory(
				player,
				recipeSlotToRequiredItemStack,
				craftingSlots,
				inventorySlots,
				transferAsCompleteSets,
					itemTransferAmount
			);

			if (foundItemsInSet.isEmpty()) {
				break;
			}

			// Merge the contents of the temporary map with the result map.
			Set<Slot> fullSlots = merge(recipeSlotToResult, foundItemsInSet);

			// to avoid overfilling slots, remove any requirements that have been met
			for (Slot fullSlot : fullSlots) {
				recipeSlotToRequiredItemStack.remove(fullSlot);
			}
		}

		return recipeSlotToResult;
	}

	private static Map<Slot, ItemStack> removeOneSetOfItemsFromInventory(
		Player player,
		Map<Slot, ItemStackWithSlotHint> recipeSlotToRequiredItemStack,
		List<Slot> craftingSlots,
		List<Slot> inventorySlots,
		boolean transferAsCompleteSets,
		List<Integer> itemTransferAmounts) {
		Map<Slot, ItemStack> originalSlotContents = null;
		if (transferAsCompleteSets) {
			// We only need to create a new map for each set iteration if we're transferring as complete sets.
			originalSlotContents = new HashMap<>();
		}

		// This map holds items found for each set iteration. Its contents are added to the result map
		// after each complete set iteration. If we are transferring as complete sets, this allows
		// us to simply ignore the map's contents when a complete set isn't found.
		final Map<Slot, ItemStack> foundItemsInSet = new LinkedHashMap<>(recipeSlotToRequiredItemStack.size());

		//TODO order
		int index=0;
		for (Map.Entry<Slot, ItemStackWithSlotHint> entry : recipeSlotToRequiredItemStack.entrySet()) { // for each item in set
			final Slot recipeSlot = entry.getKey();
			final ItemStack requiredStack = entry.getValue().stack;
			final Slot hint = entry.getValue().hint;

			// Locate a slot that has what we need.
			final Slot sourceSlot = getSlotWithStack(player, requiredStack, craftingSlots, inventorySlots, hint)
				.orElse(null);
			if (sourceSlot != null) {
				// the item was found

				// Keep a copy of the slot's original contents in case we need to roll back.
				if (originalSlotContents != null && !originalSlotContents.containsKey(sourceSlot)) {
					originalSlotContents.put(sourceSlot, sourceSlot.getItem().copy());
				}

				// Reduce the size of the found slot.
                ItemStack removedItemStack;
                if(itemTransferAmounts.isEmpty())
				{
                    removedItemStack = sourceSlot.safeTake(1, Integer.MAX_VALUE, player);
                }
				else {
                    removedItemStack = sourceSlot.safeTake(itemTransferAmounts.get(index), Integer.MAX_VALUE, player);
                }
                foundItemsInSet.put(recipeSlot, removedItemStack);
            } else {
				// We can't find any more slots to fulfill the requirements.

				if (transferAsCompleteSets) {
					// Since the full set requirement wasn't satisfied, we need to roll back any
					// slot changes we've made during this set iteration.
					for (Map.Entry<Slot, ItemStack> slotEntry : originalSlotContents.entrySet()) {
						ItemStack stack = slotEntry.getValue();
						Slot slot = slotEntry.getKey();
						slot.set(stack);
					}
					return Map.of();
				}
			}
			index++;
		}
		return foundItemsInSet;
	}

	private static Set<Slot> merge(Map<Slot, ItemStack> result, Map<Slot, ItemStack> addition) {
		Set<Slot> fullSlots = new HashSet<>();

		addition.forEach((slot, itemStack) -> {
			assert itemStack.getCount() == 1;

			ItemStack resultItemStack = result.get(slot);
			if (resultItemStack == null) {
				resultItemStack = itemStack;
				result.put(slot, resultItemStack);
			} else {
				assert ItemStack.isSameItemSameComponents(resultItemStack, itemStack);
				resultItemStack.grow(itemStack.getCount());
			}
			if (resultItemStack.getCount() == slot.getMaxStackSize(resultItemStack)) {
				fullSlots.add(slot);
			}
		});

		return fullSlots;
	}

	private static Optional<Slot> getSlotWithStack(Player player, ItemStack stack, List<Slot> craftingSlots, List<Slot> inventorySlots, Slot hint) {
		return getSlotWithStack(player, craftingSlots, stack)
			.or(() -> getValidatedHintSlot(player, stack, hint))
			.or(() -> getSlotWithStack(player, inventorySlots, stack));
	}

	private static Optional<Slot> getValidatedHintSlot(Player player, ItemStack stack, Slot hint) {
		if (isValidAndMatches(player, hint, stack)) {
			return Optional.of(hint);
		}

		return Optional.empty();
	}

	private static void stowItems(Player player, List<Slot> inventorySlots, List<ItemStack> itemStacks) {
		for (ItemStack itemStack : itemStacks) {
			ItemStack remainder = stowItem(player, inventorySlots, itemStack);
			if (!remainder.isEmpty()) {
				if (!player.getInventory().add(remainder)) {
					player.drop(remainder, false);
				}
			}
		}
	}

	private static ItemStack stowItem(Player player, Collection<Slot> slots, ItemStack stack) {
		if (stack.isEmpty()) {
			return ItemStack.EMPTY;
		}

		ItemStack remainder = stack.copy();

		// Add to existing stacks first
		for (Slot slot : slots) {
			if (!slot.mayPickup(player)) {
				continue;
			}
			final ItemStack inventoryStack = slot.getItem();
			if (!inventoryStack.isEmpty() && inventoryStack.isStackable()) {
				remainder = slot.safeInsert(remainder);
				if (remainder.isEmpty()) {
					return ItemStack.EMPTY;
				}
			}
		}

		// Try adding to empty slots
		for (Slot slot : slots) {
			if (slot.getItem().isEmpty()) {
				remainder = slot.safeInsert(remainder);
				if (remainder.isEmpty()) {
					return ItemStack.EMPTY;
				}
			}
		}

		return remainder;
	}

	/**
	 * Get the slot which contains a specific itemStack.
	 *
	 * @param slots     the slots in the container to search
	 * @param itemStack the itemStack to find
	 * @return the slot that contains the itemStack. returns null if no slot contains the itemStack.
	 */
	private static Optional<Slot> getSlotWithStack(Player player, Collection<Slot> slots, ItemStack itemStack) {
		return slots.stream()
			.filter(slot -> isValidAndMatches(player, slot, itemStack))
			.findFirst();
	}

	private static boolean isValidAndMatches(Player player, Slot slot, ItemStack stack) {
		ItemStack containedStack = slot.getItem();
		return ItemStack.isSameItemSameComponents(stack, containedStack) &&
			slot.allowModification(player);
	}

	private record ItemStackWithSlotHint(Slot hint, ItemStack stack) {}
}
