package com.civclassic.oldenchanting;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import net.md_5.bungee.api.ChatColor;
import net.minecraft.server.v1_14_R1.ContainerEnchantTable;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.craftbukkit.v1_14_R1.inventory.CraftInventoryView;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownExpBottle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExpEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ExpBottleEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.projectiles.ProjectileSource;
import vg.civcraft.mc.civmodcore.ACivMod;
import vg.civcraft.mc.civmodcore.api.BlockAPI;
import vg.civcraft.mc.civmodcore.api.EntityAPI;
import vg.civcraft.mc.civmodcore.api.ItemAPI;
import vg.civcraft.mc.civmodcore.api.RecipeAPI;
import vg.civcraft.mc.civmodcore.itemHandling.ItemMap;
import vg.civcraft.mc.civmodcore.util.NullCoalescing;

public class OldEnchanting extends ACivMod implements Listener {

	private static final Random RANDOM = new SecureRandom();

	private static final ItemStack LAPIS_ITEM = new ItemStack(Material.LAPIS_LAZULI, 64);

	private static final ItemStack EMERALD_ITEM = new ItemStack(Material.EMERALD, 1);

	private static Field tableRandomField;

	static {
		try {
			Class<ContainerEnchantTable> tableClass = ContainerEnchantTable.class;
			tableRandomField = tableClass.getDeclaredField("h");
			tableRandomField.setAccessible(true);
			Field modifiersField = Field.class.getDeclaredField("modifiers");
			modifiersField.setAccessible(true);
			modifiersField.setInt(tableRandomField, tableRandomField.getModifiers() & ~Modifier.FINAL);
		}
		catch (IllegalArgumentException | IllegalAccessException | NoSuchFieldException | SecurityException e) {
			e.printStackTrace();
		}
	}

	private ShapelessRecipe emeraldToExp;

	private ShapedRecipe expToEmerald;

	private PacketAdapter hideEnchantsAdapter;

	private boolean hideEnchants;

	private boolean fillLapis;

	private boolean randomiseEnchants;

	private boolean fixEnchantCosts;

	private double experienceModifier;

	private double lootModifier;

	private boolean emeraldCrafting;

	private boolean emeraldLeveling;

	private boolean disableGrindExp;

	private boolean preventOrbExp;

	private boolean directBottleExp;

	private boolean infiniteRepair;

	private int maxRepairCost;

	private boolean constantBottleExp;

	private int expPerBottle;

	private boolean allowExpRecovery;

	private boolean disableEnchantedBookCreation;

	private boolean disableEnchantedBookUsage;

	private final Map<EntityType, Double> entityExpDropModifiers = new HashMap<>();

	@Override
	public void onEnable() {
		this.useNewCommandHandler = false;
		super.onEnable();
		saveDefaultConfig();
		FileConfiguration config = getConfig();
		// Recipe that crafts Bottles o' Enchanting from Emeralds
		this.emeraldToExp = new ShapelessRecipe(new NamespacedKey(this, "emeraldToBottle"),
				new ItemStack(Material.EXPERIENCE_BOTTLE, 9));
		this.emeraldToExp.addIngredient(Material.EMERALD);
		// Recipe that crafts Emeralds from Bottles o' Enchanting
		this.expToEmerald = new ShapedRecipe(new NamespacedKey(this, "bottleToEmerald"), EMERALD_ITEM);
		this.expToEmerald.shape("xxx", "xxx", "xxx");
		this.expToEmerald.setIngredient('x', Material.EXPERIENCE_BOTTLE);
		// Hides what enchantment will be granted within the Enchanting Table GUI
		this.hideEnchants = config.getBoolean("hide_enchants", true);
		if (this.hideEnchants) {
			this.hideEnchantsAdapter = new PacketAdapter(this, PacketType.Play.Server.WINDOW_DATA) {
				@Override
				public void onPacketSending(PacketEvent event) {
					PacketContainer packet = event.getPacket();
					int property = packet.getIntegers().read(1);
					switch (property) {
						case 3:
						case 4:
						case 5:
						case 6:
							packet.getIntegers().write(2, -1);
					}
				}
			};
			ProtocolLibrary.getProtocolManager().addPacketListener(this.hideEnchantsAdapter);
		}
		// Automatically fills the consumable slot with the Enchanting Table GUI with Lapis Lazuli
		// NOTE: The Lapis Lazuli cannot be removed by the player
		this.fillLapis = config.getBoolean("fill_lapis", true);
		if (this.fillLapis) {
			// Puts Lapis Lazuli in Enchanting Table GUIs if any are active which may happen during a /reload
			for (Player player : Bukkit.getOnlinePlayers()) {
				InventoryView inventory = player.getOpenInventory();
				if (inventory.getType() != InventoryType.ENCHANTING) {
					continue;
				}
				inventory.setItem(1, LAPIS_ITEM.clone());
			}
		}
		// Randomises the enchantment offers each time the item is placed in an Enchanting Table
		this.randomiseEnchants = config.getBoolean("randomise_enchants", config.getBoolean("randomize_enchants", true));
		// Enables older, more expensive enchanetments costs where good enchantments cost 30 levels.
		this.fixEnchantCosts = config.getBoolean("fix_enchantment_costs", true);
		// Experience modifier, all experience drops will be multiplied by this
		// NOTE: Does not apply to player exp
		// NOTE: Modifier must be zero or greater
		this.experienceModifier = config.getDouble("experience_modifier", config.getDouble("xpmod", 0.2d));
		if (this.experienceModifier < 0.0d) {
			warning("Experience modifier [" + this.experienceModifier + "] is unsupported, defaulting to 0.2");
			this.experienceModifier = 0.2d;
		}
		// Loot modifier, multiply the amount of exp dropped from each level of Looting
		// NOTE: Does not apply to player exp
		// NOTE: Modifier must be zero or greater
		this.lootModifier = config.getDouble("loot_modifier", config.getDouble("loot_mult", 1.5d));
		if (this.lootModifier < 0.0d) {
			warning("Loot modifier [" + this.lootModifier + "] is unsupported, defaulting to 1.5");
			this.lootModifier = 1.5d;
		}
		// Enables xp bottles to be crafted from emeralds and vice versa
		this.emeraldCrafting = config.getBoolean("emerald_crafting", true);
		if (this.emeraldCrafting) {
			RecipeAPI.registerRecipe(this.emeraldToExp);
			RecipeAPI.registerRecipe(this.expToEmerald);
		}
		// Enables gaining xp from emeralds without needing to craft and use xp bottles, if emeraldCrafting is enabled
		this.emeraldLeveling = config.getBoolean("emerald_leveling", true);
		// Disables exp from mobs, fishing, mining, breeding, furnace extracting, etc
		this.disableGrindExp = config.getBoolean("disable_grind_exp", true);
		// Prevents exp orbs from granting xp
		// NOTE: This will not prevent exp orbs from spawning
		this.preventOrbExp = config.getBoolean("prevent_orb_exp", true);
		// Ensures that the player who threw the xp bottle is the one to get the xp
		// NOTE: experienceModifier will not apply to directly granted exp
		this.directBottleExp = config.getBoolean("direct_bottle_exp", true);
		// Backwards compatibility check
		if (config.getBoolean("block_natural_exp", false)) {
			this.disableGrindExp = true;
			this.preventOrbExp = true;
			this.directBottleExp = true;
		}
		// Allows items to be repaired infinitely, will require a level cap
		this.infiniteRepair = config.getBoolean("infinite_repair", config.getBoolean("infinite_enchant", true));
		// Defines the maximum repair cost of an item if infiniteRepair is enabled
		// NOTE: Value must be two or greater
		this.maxRepairCost = config.getInt("max_repair_cost", 33);
		if (this.maxRepairCost < 2) {
			warning("Maximum repair cost [" + this.maxRepairCost + "] is unsupported, defaulting to 33");
			this.maxRepairCost = 33;
		}
		// Ensures that xp bottles produce a set amount of exp
		this.constantBottleExp = config.getBoolean("constant_bottle_exp", true);
		// Defines the set amount of xp that xp bottles will produce if constantBottleEXP is enabled
		// NOTE: Value must be a positive integer
		this.expPerBottle = config.getInt("exp_per_bottle", 10);
		if (this.expPerBottle <= 0) {
			warning("Experience per bottle [" + this.expPerBottle + "] is unsupported, defaulting to 10");
			this.expPerBottle = 10;
		}
		// Allows player's to store their levels in bottles if constantBottleEXP is enabled
		this.allowExpRecovery = config.getBoolean("allow_exp_recovery", true);
		// Disallows players from creating enchanted books
		this.disableEnchantedBookCreation = config.getBoolean("disable_enchanted_book_creation", true);
		// Disallows players from using enchanted books
		this.disableEnchantedBookUsage = config.getBoolean("disable_enchanted_book_usage", true);
		// Modifies the exp drops for specific mob types, which will be used in lieu of experienceModifier
		// NOTE: Players have an implicit modifier of 1.0
		// NOTE: NOTE: Modifiers must be zero or greater
		this.entityExpDropModifiers.clear();
		this.entityExpDropModifiers.put(EntityType.PLAYER, 1.0d);
		ConfigurationSection entities = config.getConfigurationSection("entities");
		if (entities != null) {
			for (String key : entities.getKeys(false)) {
				EntityType type = EntityAPI.getEntityType(key);
				if (type == null) {
					warning("EntityType [" + key + "] does not exist, skipping.");
					continue;
				}
				double modifier = entities.getDouble(key, 1.0d);
				if (modifier < 0.0d) {
					warning("Experience modifier [" + modifier + "] for [" + key + "] is unsupported, defaulting to 1.0");
					modifier = 1.0d;
				}
				this.entityExpDropModifiers.put(type, modifier);
			}
		}
		// Start listening to events
		registerListener(this);
	}

	@Override
	public void onDisable() {
		// Unregisters the hide enchantments adapter
		if (this.hideEnchants) {
			NullCoalescing.exists(this.hideEnchantsAdapter, (adapter) ->
					ProtocolLibrary.getProtocolManager().removePacketListener(adapter));
		}
		// Remove the Lapis Lazuli from Enchanting Tables to prevent dupes
		if (this.fillLapis) {
			for (Player player : Bukkit.getOnlinePlayers()) {
				InventoryView inventory = player.getOpenInventory();
				if (inventory.getType() != InventoryType.ENCHANTING) {
					continue;
				}
				inventory.setItem(1, null);
			}
		}
		// Remove the emerald crafting recipes
		if (this.emeraldCrafting) {
			RecipeAPI.removeRecipe(this.emeraldToExp);
			RecipeAPI.removeRecipe(this.expToEmerald);
		}
		// Clear our entity experience modifiers
		this.entityExpDropModifiers.clear();
		super.onDisable();
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onEntityDeath(EntityDeathEvent event) {
		// If exp from mobs is disabled, prevent exp from being dropped
		if (this.disableGrindExp) {
			event.setDroppedExp(0);
			return;
		}
		LivingEntity entity = event.getEntity();
		EntityType entityType = entity.getType();
		int experience = event.getDroppedExp();
		// If there's an exp modifier for this mob type, apply it
		if (this.entityExpDropModifiers.containsKey(entityType)) {
			experience = applyModifier(experience, this.entityExpDropModifiers.get(entityType));
		}
		// Otherwise apply the general experience modifier
		// DOES NOT APPLY TO XP DROPPED FROM PLAYERS!
		else if (entityType != EntityType.PLAYER) {
			experience = applyModifier(experience, this.experienceModifier);
		}
		// If the entity was killed by a player with Looting, apply modifier
		// DOES NOT APPLY TO XP DROPPED FROM PLAYERS!
		if (entityType != EntityType.PLAYER) {
			Player killer = entity.getKiller();
			if (killer != null) {
				ItemStack held = killer.getInventory().getItemInMainHand();
				if (ItemAPI.isValidItem(held) && held.hasItemMeta()) {
					ItemMeta meta = held.getItemMeta();
					if (meta != null && meta.hasEnchant(Enchantment.LOOT_BONUS_MOBS)) {
						double modifier = this.lootModifier * meta.getEnchantLevel(Enchantment.LOOT_BONUS_MOBS);
						experience = applyModifier(experience, modifier);
					}
				}
			}
		}
		// Apple the modified experience
		event.setDroppedExp(Math.max(0, experience));
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onBlockExp(BlockExpEvent event) {
		// If exp from mining is disabled, prevent exp from being dropped
		if (this.disableGrindExp) {
			event.setExpToDrop(0);
		}
		// Otherwise apply general experience modifier
		else {
			event.setExpToDrop(applyModifier(event.getExpToDrop(), this.experienceModifier));
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onFurnaceExp(FurnaceExtractEvent event) {
		// If exp from furnaces is disabled, prevent exp from being dropped
		if (this.disableGrindExp) {
			event.setExpToDrop(0);
		}
		// Otherwise apply general experience modifier
		else {
			event.setExpToDrop(applyModifier(event.getExpToDrop(), this.experienceModifier));
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onFishingExp(PlayerFishEvent event) {
		// If exp from fishing is disabled, prevent exp from being dropped
		if (this.disableGrindExp) {
			event.setExpToDrop(0);
		}
		// Otherwise apply general experience modifier
		else {
			event.setExpToDrop(applyModifier(event.getExpToDrop(), this.experienceModifier));
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onBreedExp(EntityBreedEvent event) {
		// If exp from breeding is disabled, prevent exp from being given
		if (this.disableGrindExp) {
			event.setExperience(0);
		}
		// Otherwise apply general experience modifier
		else {
			event.setExperience(applyModifier(event.getExperience(), this.experienceModifier));
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onMerchantRecipe(InventoryOpenEvent event) {
		// If not disabling merchant recipe xp, back out
		if (!this.disableGrindExp) {
			return;
		}
		// If not a merchant inventory, back out
		if (!(event.getInventory() instanceof Merchant)) {
			return;
		}
		// Disable all exp rewards
		for (MerchantRecipe recipe : ((Merchant) event.getInventory()).getRecipes()) {
			recipe.setExperienceReward(false);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onExpChange(PlayerExpChangeEvent event) {
		// If exp orbs are disabled, prevent exp from applying
		if (this.preventOrbExp) {
			event.setAmount(0);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onExpBottle(ExpBottleEvent event) {
		// If xp bottles contain a constant amount of experience, set it
		if (this.constantBottleExp) {
			event.setExperience(this.expPerBottle);
		}
		// If exp from xp bottles should be applied directly, do so
		if (this.directBottleExp) {
			ThrownExpBottle bottle = event.getEntity();
			ProjectileSource thrower = bottle.getShooter();
			if (thrower instanceof Player) {
				Player player = (Player) thrower;
				player.giveExp(event.getExperience());
				// Play the experience sound as no experience orbs will be spawned
				// Credit to Team CoFH for the random pitch generator, see their code below
				// https://github.com/CoFH/ThermalFoundation/blob/1.12/src/main/java/cofh/thermalfoundation/item/tome/ItemTomeExperience.java#L268
				// This is a reasonable use of their "Copy portions of this code for use in
				// other projects." clause.
				player.getWorld().playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.1F,
						(RANDOM.nextFloat() - RANDOM.nextFloat()) * 0.35f + 0.9f);
				event.setExperience(0);
				bottle.teleport(player);
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onEmeraldExp(PlayerInteractEvent event) {
		// If emerald crafting is not enabled, back out
		if (!this.emeraldCrafting) {
			return;
		}
		// If emerald leveling is not enabled, back out
		if (!this.emeraldLeveling) {
			return;
		}
		// If the action is not a right click, back out
		switch (event.getAction()) {
			case RIGHT_CLICK_AIR:
			case RIGHT_CLICK_BLOCK:
				break;
			default:
				return;
		}
		// If the item is not an emerald, back out
		Player player = event.getPlayer();
		PlayerInventory inventory = player.getInventory();
		ItemStack held = inventory.getItemInMainHand();
		if (ItemAPI.isValidItem(held) || !held.isSimilar(EMERALD_ITEM)) {
			return;
		}
		// If the item is lored, it's probably a custom item, back out
		if (held.hasItemMeta()) {
			ItemMeta meta = held.getItemMeta();
			if (meta != null && meta.hasLore()) {
				return;
			}
		}
		// If the amount is unsupported, back out
		int amount = held.getAmount();
		if (amount <= 0) {
			return;
		}
		// Determine how much exp should be given
		int experience = 0;
		if (this.constantBottleExp) {
			experience = this.expPerBottle * 9;
		}
		else {
			// Simulate nine thrown bottles, which drop between 3-11 experience
			// https://minecraft.gamepedia.com/Bottle_o%27_Enchanting
			for (int i = 0; i < 9; i++) {
				experience += RANDOM.nextInt(11 - 3 + 1) + 3;
			}
		}
		// Apply the exp to the player at the cost of an emerald
		player.giveExp(experience);
		if (amount == 1) {
			inventory.setItemInMainHand(null);
		}
		else {
			held.setAmount(--amount);
			inventory.setItemInMainHand(held);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerExpRecovery(PlayerInteractEvent event) {
		// If exp recovery is not enabled, back out
		if (!this.allowExpRecovery) {
			return;
		}
		// If constant bottle xp is not enabled, back out
		if (!this.constantBottleExp) {
			return;
		}
		// If the action wasn't a block punch, back out
		if (event.getAction() != Action.LEFT_CLICK_BLOCK) {
			return;
		}
		// If the block being punched isn't an Enchanting Table, back out
		Block block = event.getClickedBlock();
		if (!BlockAPI.isValidBlock(block)) {
			return;
		}
		if (block.getType() != Material.ENCHANTING_TABLE) {
			return;
		}
		// If the player is not holding glass bottles, back out
		Player player = event.getPlayer();
		PlayerInventory inventory = player.getInventory();
		ItemStack held = inventory.getItemInMainHand();
		if (held.getType() != Material.GLASS_BOTTLE) {
			return;
		}
		// If the player does not have enough exp, back out
		int totalExp = computeCurrentXP(player);
		if (totalExp < this.expPerBottle) {
			return;
		}
		// Attempt to create exp bottles
		createExpBottles(player, totalExp);
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPrepareBookEnchant(PrepareItemEnchantEvent event) {
		// If creating enchanted books is enabled, back out
		if (!this.disableEnchantedBookCreation) {
			return;
		}
		// If the item is not a book, back out
		ItemStack item = event.getItem();
		if (!ItemAPI.isValidItem(item)) {
			return;
		}
		if (item.getType() != Material.BOOK) {
			return;
		}
		// Otherwise cancel the event
		event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPrepareBookUsage(PrepareAnvilEvent event) {
		// If creating enchanted books is enabled, back out
		if (!this.disableEnchantedBookUsage) {
			return;
		}
		// If there's an enchanted book, prevent there from being a result
		AnvilInventory inventory = event.getInventory();
		if (inventory.first(Material.ENCHANTED_BOOK) != -1) {
			if (event.getResult() != null) {
				event.setResult(null);
				// This is needed because of client side shenanigans
				Bukkit.getScheduler().runTaskLater(this, () -> {
					for (HumanEntity viewer : inventory.getViewers()) {
						if (viewer instanceof Player) {
							((Player) viewer).updateInventory();
						}
					}
				}, 1L);
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPrepareItemEnchant(PrepareItemEnchantEvent event) {
		CraftInventoryView view = (CraftInventoryView) event.getView();
		ContainerEnchantTable table = (ContainerEnchantTable) view.getHandle();
		// If randomise enchants is enabled, randomise the offers
		if (this.randomiseEnchants) {
			try {
				tableRandomField.set(table, RANDOM);
			}
			catch (IllegalArgumentException | IllegalAccessException e) {
				e.printStackTrace();
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onAnvilRepair(PrepareAnvilEvent event) {
		// If infinite repair is not enabled, back out
		if (!this.infiniteRepair) {
			return;
		}
		// If the offer is not valid, back out
		ItemStack result = event.getResult();
		if (!ItemAPI.isValidItem(result)) {
			return;
		}
		ItemAPI.handleItemMeta(result, (Repairable meta) -> {
			int newRepairCost = this.maxRepairCost - 2;
			if (meta.getRepairCost() < newRepairCost) {
				return false;
			}
			meta.setRepairCost(newRepairCost);
			return true;
		});
		event.setResult(result);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onEnchantItem(EnchantItemEvent event) {
		if (this.fixEnchantCosts) {
			Player player = event.getEnchanter();
			if (player.getLevel() < event.getExpLevelCost()) {
				event.setCancelled(true);
				return;
			}
			// The level cost of enchantments is always the button index + 1, assuming the server is Bukkit.
			// And so if we want the enchantment to actually cost 30 levels as required by the ExpLevelCost, we need to
			// make up for the difference with a manual level reduction of the remainder.
			int bukkitLevelCost = event.whichButton() + 1;
			int levelRemainder = event.getExpLevelCost() - bukkitLevelCost;
			int newLevel = player.getLevel() - levelRemainder;
			player.setLevel(newLevel);
		}
		// And refill the Lapis Lazuli
		if (this.fillLapis) {
			event.getInventory().setItem(1, LAPIS_ITEM.clone());
		}
	}

	// TODO: This function causes weirdness when you place Lapis Lazuli in the item
	// to enchant slot. It should be
	// modified to only cancel the event if items are being added to or removed from
	// the consumable slot.
	@EventHandler(priority = EventPriority.HIGHEST)
	public void onInventoryClick(InventoryClickEvent event) {
		// If the inventory is creative, back out
		if (event instanceof InventoryCreativeEvent) {
			return;
		}
		// If fillLapis is not enabled, back out
		if (!this.fillLapis) {
			return;
		}
		// If the inventory is not that of an Enchanting Table, back out
		Inventory inventory = event.getClickedInventory();
		if (inventory == null || inventory.getType() != InventoryType.ENCHANTING) {
			return;
		}
		// If the current item is not Lapis Lazuli, back out
		ItemStack currentItem = event.getCurrentItem();
		if (currentItem == null || !currentItem.isSimilar(LAPIS_ITEM)) {
			return;
		}
		// Otherwise prevent altering of the Lapis Lazuli
		event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onOpenEnchantingTable(InventoryOpenEvent event) {
		// If fillLapis is not enabled, back out
		if (!this.fillLapis) {
			return;
		}
		// If the inventory is not that of an Enchanting Table, back out
		Inventory inventory = event.getInventory();
		if (inventory.getType() != InventoryType.ENCHANTING) {
			return;
		}
		// Otherwise fill with Lapis Lazuli
		event.getInventory().setItem(1, LAPIS_ITEM.clone());
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onCloseEnchantingTable(InventoryCloseEvent event) {
		// If fillLapis is not enabled, back out
		if (!this.fillLapis) {
			return;
		}
		// If the inventory is not that of an Enchanting Table, back out
		Inventory inventory = event.getInventory();
		if (inventory.getType() != InventoryType.ENCHANTING) {
			return;
		}
		// Otherwise emtpy out Lapis Lazuli
		inventory.setItem(1, null);
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onBreakEnchantingTable(BlockBreakEvent event) {
		// If fillLapis is not enabled, back out
		if (!this.fillLapis) {
			return;
		}
		// If block is not an Enchanting Table, back out
		Block block = event.getBlock();
		if (block.getType() != Material.ENCHANTING_TABLE) {
			return;
		}
		// Remove Lapis Lazuli from the drops
		block.getDrops().removeIf(drop -> drop.isSimilar(LAPIS_ITEM));
	}

	private int applyModifier(int experience, double modifier) {
		return (int) Math.max(0, Math.ceil(experience * modifier));
	}

	private int computeCurrentXP(Player player) {
		float currentLevel = player.getLevel();
		float progress = player.getExp();
		float a = 1f, b = 6f, c = 0f, x = 2f, y = 7f;
		if (currentLevel > 16 && currentLevel <= 31) {
			a = 2.5f;
			b = -40.5f;
			c = 360f;
			x = 5f;
			y = -38f;
		}
		else if (currentLevel >= 32) {
			a = 4.5f;
			b = -162.5f;
			c = 2220f;
			x = 9f;
			y = -158f;
		}
		return (int) Math
				.floor(a * currentLevel * currentLevel + b * currentLevel + c + progress * (x * currentLevel + y));
	}

	private void createExpBottles(Player player, int totalExp) {
		ItemMap inv = new ItemMap(player.getInventory());
		int bottles = inv.getAmount(new ItemStack(Material.GLASS_BOTTLE));
		int xpavailable = totalExp / this.expPerBottle;
		int remove = Math.min(bottles, xpavailable);
		if (remove == 0) {
			return;
		}
		boolean noSpace = false;
		int bottleCount = 0;
		ItemMap removeMap = new ItemMap();
		removeMap.addItemAmount(new ItemStack(Material.GLASS_BOTTLE), remove);
		for (ItemStack is : removeMap.getItemStackRepresentation()) {
			int initialAmount = is.getAmount();
			player.getInventory().removeItem(is);
			is.setType(Material.EXPERIENCE_BOTTLE);
			HashMap<Integer, ItemStack> result = player.getInventory().addItem(is);
			if (!result.isEmpty()) {
				is.setType(Material.GLASS_BOTTLE);
				player.getInventory().addItem(is);
				noSpace = true;
				break;
			}
			else {
				bottleCount += initialAmount;
			}
		}
		if (bottleCount > 0) {
			int endXP = totalExp - bottleCount * this.expPerBottle;
			player.setLevel(0);
			player.setExp(0);
			player.giveExp(endXP);
			player.sendMessage(ChatColor.GREEN + "Created " + bottleCount + " XP bottles.");
		}
		if (noSpace) {
			player.sendMessage(ChatColor.RED + "Not enough space in inventory for all XP bottles.");
		}
	}

}
