package com.raoulvdberge.refinedstorage.apiimpl.autocrafting;

import com.raoulvdberge.refinedstorage.api.autocrafting.ICraftingPattern;
import com.raoulvdberge.refinedstorage.api.autocrafting.ICraftingPatternContainer;
import com.raoulvdberge.refinedstorage.apiimpl.API;
import com.raoulvdberge.refinedstorage.apiimpl.autocrafting.registry.CraftingTaskFactory;
import com.raoulvdberge.refinedstorage.apiimpl.util.OneSixMigrationHelper;
import com.raoulvdberge.refinedstorage.item.ItemPattern;
import com.raoulvdberge.refinedstorage.util.StackUtils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.List;

public class CraftingPattern implements ICraftingPattern {
    private ICraftingPatternContainer container;
    private ItemStack stack;
    private boolean processing;
    private boolean oredict;
    private boolean valid;
    private IRecipe recipe;
    private List<NonNullList<ItemStack>> inputs = new ArrayList<>();
    private NonNullList<ItemStack> outputs = NonNullList.create();
    private NonNullList<ItemStack> byproducts = NonNullList.create();

    public CraftingPattern(World world, ICraftingPatternContainer container, ItemStack stack) {
        if (!OneSixMigrationHelper.isValidOneSixPattern(stack)) {
            this.valid = false;

            return;
        }

        this.container = container;
        this.stack = stack;
        this.processing = ItemPattern.isProcessing(stack);
        this.oredict = ItemPattern.isOredict(stack);

        if (processing) {
            for (int i = 0; i < 9; ++i) {
                ItemStack input = ItemPattern.getInputSlot(stack, i);

                if (input == null) {
                    inputs.add(NonNullList.create());
                } else if (oredict) {
                    NonNullList<ItemStack> equivalent = NonNullList.create();

                    equivalent.add(input.copy());

                    for (ItemStack equivalentStack : StackUtils.getEquivalentStacks(input)) {
                        equivalent.add(ItemHandlerHelper.copyStackWithSize(equivalentStack, input.getCount()));
                    }

                    inputs.add(equivalent);
                } else {
                    inputs.add(NonNullList.from(ItemStack.EMPTY, input));
                }

                ItemStack output = ItemPattern.getOutputSlot(stack, i);
                if (output != null) {
                    this.valid = true; // As soon as we have one output, we are valid.

                    outputs.add(output);
                }
            }
        } else {
            InventoryCrafting inv = new InventoryCraftingDummy();

            for (int i = 0; i < 9; ++i) {
                ItemStack input = ItemPattern.getInputSlot(stack, i);

                inputs.add(input == null ? NonNullList.create() : NonNullList.from(ItemStack.EMPTY, input));

                if (input != null) {
                    inv.setInventorySlotContents(i, input);
                }
            }

            for (IRecipe r : CraftingManager.REGISTRY) {
                if (r.matches(inv, world)) {
                    this.recipe = r;

                    this.byproducts = recipe.getRemainingItems(inv);

                    ItemStack output = recipe.getCraftingResult(inv);

                    if (!output.isEmpty()) {
                        this.valid = true;

                        outputs.add(output);

                        if (oredict) {
                            if (recipe.getIngredients().size() > 0) {
                                inputs.clear();

                                for (int i = 0; i < recipe.getIngredients().size(); ++i) {
                                    inputs.add(i, NonNullList.from(ItemStack.EMPTY, recipe.getIngredients().get(i).getMatchingStacks()));
                                }
                            } else {
                                this.valid = false;
                            }
                        }
                    }

                    break;
                }
            }
        }
    }

    @Override
    public ICraftingPatternContainer getContainer() {
        return container;
    }

    @Override
    public ItemStack getStack() {
        return stack;
    }

    @Override
    public boolean isValid() {
        return valid;
    }

    @Override
    public boolean isProcessing() {
        return processing;
    }

    @Override
    public boolean isOredict() {
        return oredict;
    }

    @Override
    public List<NonNullList<ItemStack>> getInputs() {
        return inputs;
    }

    @Override
    public NonNullList<ItemStack> getOutputs() {
        return outputs;
    }

    public ItemStack getOutput(NonNullList<ItemStack> took) {
        if (processing) {
            throw new IllegalStateException("Cannot get crafting output from processing pattern");
        }

        if (took.size() != inputs.size()) {
            throw new IllegalArgumentException("The items that are taken (" + took.size() + ") should match the inputs for this pattern (" + inputs.size() + ")");
        }

        InventoryCrafting inv = new InventoryCraftingDummy();

        for (int i = 0; i < took.size(); ++i) {
            inv.setInventorySlotContents(i, took.get(i));
        }

        ItemStack result = recipe.getCraftingResult(inv);
        if (result.isEmpty()) {
            throw new IllegalStateException("Cannot have empty result");
        }

        return result;
    }

    @Override
    public NonNullList<ItemStack> getByproducts() {
        if (processing) {
            throw new IllegalStateException("Cannot get byproduct outputs from processing pattern");
        }

        return byproducts;
    }

    @Override
    public NonNullList<ItemStack> getByproducts(NonNullList<ItemStack> took) {
        if (processing) {
            throw new IllegalStateException("Cannot get byproduct outputs from processing pattern");
        }

        if (took.size() != inputs.size()) {
            throw new IllegalArgumentException("The items that are taken (" + took.size() + ") should match the inputs for this pattern (" + inputs.size() + ")");
        }

        InventoryCrafting inv = new InventoryCraftingDummy();

        for (int i = 0; i < took.size(); ++i) {
            inv.setInventorySlotContents(i, took.get(i));
        }

        NonNullList<ItemStack> remainingItems = recipe.getRemainingItems(inv);
        NonNullList<ItemStack> sanitized = NonNullList.create();

        for (ItemStack item : remainingItems) {
            if (!item.isEmpty()) {
                sanitized.add(item);
            }
        }

        return sanitized;
    }

    @Override
    public String getId() {
        return CraftingTaskFactory.ID;
    }

    @Override
    public boolean canBeInChainWith(ICraftingPattern other) {
        if (other.isProcessing() != processing || other.isOredict() != oredict) {
            return false;
        }

        if ((other.getInputs().size() != inputs.size()) || (other.getOutputs().size() != outputs.size())) {
            return false;
        }

        if (!processing && other.getByproducts().size() != byproducts.size()) {
            return false;
        }

        for (int i = 0; i < inputs.size(); ++i) {
            List<ItemStack> inputs = this.inputs.get(i);
            List<ItemStack> otherInputs = other.getInputs().get(i);

            if (inputs.size() != otherInputs.size()) {
                return false;
            }

            for (int j = 0; j < inputs.size(); ++j) {
                if (!API.instance().getComparer().isEqual(inputs.get(j), otherInputs.get(j))) {
                    return false;
                }
            }
        }

        for (int i = 0; i < outputs.size(); ++i) {
            if (!API.instance().getComparer().isEqual(outputs.get(i), other.getOutputs().get(i))) {
                return false;
            }
        }

        if (!processing) {
            for (int i = 0; i < byproducts.size(); ++i) {
                if (!API.instance().getComparer().isEqual(byproducts.get(i), other.getByproducts().get(i))) {
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public int getChainHashCode() {
        int result = 0;

        result = 31 * result + (processing ? 1 : 0);
        result = 31 * result + (oredict ? 1 : 0);

        for (List<ItemStack> inputs : this.inputs) {
            for (ItemStack input : inputs) {
                result = 31 * result + API.instance().getItemStackHashCode(input, true);
            }
        }

        for (ItemStack output : this.outputs) {
            result = 31 * result + API.instance().getItemStackHashCode(output, true);
        }

        for (ItemStack byproduct : this.byproducts) {
            result = 31 * result + API.instance().getItemStackHashCode(byproduct, true);
        }

        return result;
    }

    private class InventoryCraftingDummy extends InventoryCrafting {
        public InventoryCraftingDummy() {
            super(new Container() {
                @Override
                public boolean canInteractWith(EntityPlayer player) {
                    return true;
                }
            }, 3, 3);
        }
    }
}
