package mezz.jei.library.util;

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.common.platform.IPlatformModHelper;
import mezz.jei.common.platform.Services;
import mezz.jei.api.ingredients.IIngredientSupplier;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class RecipeErrorUtil {
	private static final Logger LOGGER = LogManager.getLogger();

	private RecipeErrorUtil() {
	}

	public static <T> String getInfoFromRecipe(T recipe, IRecipeCategory<T> recipeCategory, IIngredientManager ingredientManager) {
		StringBuilder recipeInfoBuilder = new StringBuilder();
		String recipeName = getNameForRecipe(recipe);
		recipeInfoBuilder.append(recipeName);

		IIngredientSupplier ingredientSupplier = IngredientSupplierHelper.getIngredientSupplier(recipe, recipeCategory, ingredientManager);

		recipeInfoBuilder.append(" {");
		recipeInfoBuilder.append("\n  Outputs:");
		appendRoleData(ingredientSupplier, RecipeIngredientRole.OUTPUT, recipeInfoBuilder, ingredientManager);

		recipeInfoBuilder.append("\n  Inputs:");
		appendRoleData(ingredientSupplier, RecipeIngredientRole.INPUT, recipeInfoBuilder, ingredientManager);

		recipeInfoBuilder.append("\n  Catalysts:");
		appendRoleData(ingredientSupplier, RecipeIngredientRole.CATALYST, recipeInfoBuilder, ingredientManager);

		recipeInfoBuilder.append("\n}");

		return recipeInfoBuilder.toString();
	}

	private static void appendRoleData(IIngredientSupplier ingredientSupplier, RecipeIngredientRole role, StringBuilder recipeInfoBuilder, IIngredientManager ingredientManager) {
		ingredientSupplier.getIngredients(role)
			.stream()
			.map(ITypedIngredient::getType)
			.distinct()
			.forEach(ingredientType -> {
				String ingredientOutputInfo = getIngredientInfo(ingredientType, role, ingredientSupplier, ingredientManager);
				recipeInfoBuilder
					.append("\n    ")
					.append(ingredientType.getIngredientClass().getName())
					.append(": ")
					.append(ingredientOutputInfo);
			});
	}

	private static <T> String getIngredientInfo(IIngredientType<T> ingredientType, RecipeIngredientRole role, IIngredientSupplier ingredients, IIngredientManager ingredientManager) {
		List<T> ingredientList = new ArrayList<>();

		for (ITypedIngredient<?> ingredient : ingredients.getIngredients(role)) {
			ingredient.getIngredient(ingredientType)
				.ifPresent(ingredientList::add);
		}

		IIngredientHelper<T> ingredientHelper = ingredientManager.getIngredientHelper(ingredientType);

		Stream<String> stringStream = ingredientList.stream()
			.map(ingredientHelper::getErrorInfo);

		return truncatedStream(stringStream, ingredientList.size(), 10)
			.toList()
			.toString();
	}

	public static String getNameForRecipe(Object recipe) {
		return Optional.of(recipe)
			.filter(RecipeHolder.class::isInstance)
			.map(RecipeHolder.class::cast)
			.map(RecipeHolder::id)
			.map(registryName -> {
				IPlatformModHelper modHelper = Services.PLATFORM.getModHelper();
				String modId = registryName.getNamespace();
				String modName = modHelper.getModNameForModId(modId);
				return modName + " " + registryName + " " + recipe.getClass();
			})
			.orElseGet(() -> {
				try {
					return recipe.toString();
				} catch (RuntimeException e) {
					LOGGER.error("Failed recipe.toString", e);
					return recipe.getClass().toString();
				}
			});
	}

	private static Stream<String> truncatedStream(Stream<String> stream, int size, int limit) {
		if (size + 1 > limit) {
			return Stream.concat(
				stream.limit(limit),
				Stream.of(String.format("<truncated to %s elements, skipped %s>", limit, size - limit))
			);
		}
		return stream;
	}
}
