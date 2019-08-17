package com.civclassic.oldenchanting;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.v1_12_R1.inventory.CraftInventoryView;
import org.bukkit.craftbukkit.v1_12_R1.inventory.CraftItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
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
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;

import net.md_5.bungee.api.ChatColor;
import net.minecraft.server.v1_12_R1.ContainerEnchantTable;
import vg.civcraft.mc.civmodcore.itemHandling.ItemMap;

public class OldEnchanting extends JavaPlugin implements Listener {

	private static Random rand = new Random();
	
	private boolean hideEnchants;
	private boolean fillLapis;
	private boolean randomize;
	private double xpMod;
	private double lootMod;
	private boolean emeraldCrafting;
	private Map<EntityType, Double> xpModifiers;
	private boolean noExp;
	private double dispenserXPRadius;
	private int xpPerBottle;
	private boolean infiniteEnchant;
	private int maxRepairCost;
	
	@Override
	public void onEnable() {
		saveDefaultConfig();
		hideEnchants = getConfig().getBoolean("hide_enchants");
		fillLapis = getConfig().getBoolean("fill_lapis");
		randomize = getConfig().getBoolean("randomize_enchants");
		xpMod = getConfig().getDouble("xpmod");
		lootMod = getConfig().getDouble("loot_mult");
		emeraldCrafting = getConfig().getBoolean("emerald_crafting");
		if(emeraldCrafting) {
			registerRecipes();
		}
		xpModifiers = new HashMap<EntityType, Double>();
		ConfigurationSection entities = getConfig().getConfigurationSection("entities");
		for(String key : entities.getKeys(false)) {
			EntityType type = EntityType.valueOf(key);
			if(type != null) {
				xpModifiers.put(type, entities.getDouble(key));
			}
		}
		noExp = getConfig().getBoolean("block_natural_exp", true);
		dispenserXPRadius = getConfig().getDouble("unnatural_dispensed_xp_radius", 2d);
		if (dispenserXPRadius < 2) {
			getLogger().warning("Your unnatural dispenser radius (" + dispenserXPRadius + ") is smaller than two blocks and so may result is unusual behaviour.");
		}
		else if (dispenserXPRadius > 7) {
			getLogger().warning("Your unnatural dispenser radius (" + dispenserXPRadius + ") is larger than the distance xp orbs will naturally gravitate towards players.");
		}
		xpPerBottle = getConfig().getInt("exp_per_bottle", 10);
		infiniteEnchant = getConfig().getBoolean("infinite_enchant", true);
		maxRepairCost = getConfig().getInt("max_repair_cost", 35);

		getServer().getPluginManager().registerEvents(this, this);
		if(hideEnchants) {
			ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(this, PacketType.Play.Server.WINDOW_DATA) {
				public void onPacketSending(PacketEvent event) {
					PacketContainer packet = event.getPacket();
					int property = packet.getIntegers().read(1);
					switch(property) {
					case 3:
					case 4:
					case 5:
					case 6:
						packet.getIntegers().write(2, -1);
					}
				}
			});
		}
	}
	
	private void registerRecipes() {
		ItemStack emerald = new ItemStack(Material.EMERALD, 1);
		ShapedRecipe expToEmerald = new ShapedRecipe(emerald);
		expToEmerald.shape("xxx", "xxx", "xxx"); 
		expToEmerald.setIngredient('x', Material.EXP_BOTTLE);
		getServer().addRecipe(expToEmerald);
		ItemStack bottles = new ItemStack(Material.EXP_BOTTLE, 9);
		ShapelessRecipe emeraldsToExp = new ShapelessRecipe(bottles);
		emeraldsToExp.addIngredient(Material.EMERALD);
		getServer().addRecipe(emeraldsToExp);
	}
	
	@EventHandler
	public void onServerCommand(ServerCommandEvent event) {
		if(event.getCommand().equals("stop")) {
			//Close all inventories before the server shuts down so lapis doesn't get duped
			for(Player player : Bukkit.getOnlinePlayers()) {
				player.closeInventory();
			}
		}
	}
	
	@EventHandler(priority=EventPriority.HIGHEST)
	public void onEntityDeath(EntityDeathEvent event) {
		if(noExp) {
			event.setDroppedExp(0);
			return;
		}
		LivingEntity entity = event.getEntity();
		if(entity.getType() == EntityType.PLAYER) return;
		double xp = event.getDroppedExp();
		if(xp == 0) return;
		xp *= xpModifiers.containsKey(entity.getType()) ? xpModifiers.get(entity.getType()) : xpMod;
		if(entity.getKiller() != null) {
			Player killer = entity.getKiller();
			if(killer.getInventory().getItemInMainHand() != null) {
				if(killer.getInventory().getItemInMainHand().hasItemMeta() && killer.getInventory().getItemInMainHand().getItemMeta().hasEnchant(Enchantment.LOOT_BONUS_MOBS)) {
					double mod = lootMod * killer.getInventory().getItemInMainHand().getItemMeta().getEnchantLevel(Enchantment.LOOT_BONUS_MOBS);
					xp *= mod;
				}
			}
		}
		event.setDroppedExp(Math.max(1, (int)xp));
	}
	
	@EventHandler(priority=EventPriority.HIGHEST)
	public void onBlockExp(BlockExpEvent event) {
		event.setExpToDrop(noExp ? 0 : (int) Math.ceil(((double)event.getExpToDrop() * xpMod)));
	}
	
	@EventHandler
	public void onEnchantItem(EnchantItemEvent event) {
		Player player = event.getEnchanter();
		int actualCost = event.getExpLevelCost() - event.whichButton();
		Bukkit.getScheduler().scheduleSyncDelayedTask(this, () -> {
			player.setLevel(player.getLevel() - actualCost);
		});
		if(fillLapis) {
			event.getInventory().setItem(1, new ItemStack(Material.INK_SACK, 64, (short) 4));
		}
	}
	
	@EventHandler
	public void onPrepareItemEnchant(PrepareItemEnchantEvent event) {
		CraftInventoryView view = (CraftInventoryView) event.getView();
		ContainerEnchantTable table = (ContainerEnchantTable) view.getHandle();
		if(randomize) {
			table.f = rand.nextInt();
		}
	}
	
	@EventHandler
	public void onInventoryClick(InventoryClickEvent event) {
		if(event instanceof InventoryCreativeEvent) return;
		if(fillLapis && event != null && event.getClickedInventory() != null 
				&& event.getClickedInventory().getType() == InventoryType.ENCHANTING && event.getCurrentItem() != null
				&& event.getCurrentItem().getType() == Material.INK_SACK && event.getCurrentItem().getDurability() == 4) {
			event.setCancelled(true);
		}
	}
	
	@EventHandler
	public void onInventoryOpen(InventoryOpenEvent event) {
		if(fillLapis && event.getInventory().getType() == InventoryType.ENCHANTING) {
			event.getInventory().setItem(1, new ItemStack(Material.INK_SACK, 64, (short) 4));
		}
	}
	
	@EventHandler
	public void onInventoryClose(InventoryCloseEvent event) {
		if(fillLapis && event.getInventory().getType() == InventoryType.ENCHANTING) {
			event.getInventory().setItem(1, null);
		}
	}
	
	@EventHandler
	public void onBlockBreak(BlockBreakEvent event) {
		if(event.getBlock().getType() == Material.ENCHANTMENT_TABLE) {
			for(ItemStack item : event.getBlock().getDrops()) {
				if(item.getType() == Material.INK_SACK) {
					event.getBlock().getDrops().remove(item);
				}
			}
		}
	}
	
	@EventHandler
	public void onPrepareAnvil(PrepareAnvilEvent event) {
		if(!infiniteEnchant) return;
		ItemStack result = event.getResult();
		if(result != null && result.getType() != Material.AIR) {
			net.minecraft.server.v1_12_R1.ItemStack is = CraftItemStack.asNMSCopy(result);
			if(is == null) return;
			if(is.getRepairCost() > maxRepairCost -2) {
				is.setRepairCost(maxRepairCost -2);
				event.setResult(CraftItemStack.asBukkitCopy(is));
			}
		}
	}
	
	@EventHandler
	public void onFurnaceExtract(FurnaceExtractEvent event) {
		event.setExpToDrop(noExp ? 0 : (int) Math.max(1,(double) event.getExpToDrop() * xpMod));
	}
	
	@EventHandler
	public void onPlayerInteract(PlayerInteractEvent event) {
		Block b = event.getClickedBlock();
		if(event.getAction() == Action.LEFT_CLICK_BLOCK && b != null && b.getType() == Material.ENCHANTMENT_TABLE) {
			Player player = event.getPlayer();
			
			int totalExp = computeCurrentXP(player);
			
			if(player.getInventory().getItemInMainHand() != null
					&& player.getInventory().getItemInMainHand().getType() == Material.GLASS_BOTTLE
					&& totalExp >= xpPerBottle) {
				createXPBottles(player, totalExp);
			}
		}
	}

	@EventHandler
	public void onFishingXP(EntityBreedEvent event) {
		if (noExp) {
			event.setExperience(0);
		}
		else {
			event.setExperience((int) Math.ceil(event.getExperience() * xpMod));
		}
	}

	@EventHandler
	public void onBreedXP(PlayerFishEvent event) {
		if (noExp) {
			event.setExpToDrop(0);
		}
		else {
			event.setExpToDrop((int) Math.ceil(event.getExpToDrop() * xpMod));
		}
	}

	@EventHandler
	public void onMerchantXP(MerchantRecipe event) {
		if (noExp) {
			event.setExperienceReward(false);
		}
	}
	
	private void createXPBottles(Player player, int totalExp) {
		ItemMap inv = new ItemMap(player.getInventory());
		int bottles = inv.getAmount(new ItemStack(Material.GLASS_BOTTLE));
		int xpavailable = totalExp / xpPerBottle;
		int remove = Math.min(bottles, xpavailable);
		
		if(remove == 0) return;
		
		boolean noSpace = false;
		int bottleCount = 0;
		ItemMap removeMap = new ItemMap();
		
		removeMap.addItemAmount(new ItemStack(Material.GLASS_BOTTLE), remove);
		
		for(ItemStack is : removeMap.getItemStackRepresentation()) {
			int initialAmount = is.getAmount();
			
			player.getInventory().removeItem(is);
			is.setType(Material.EXP_BOTTLE);
			
			HashMap<Integer, ItemStack> result = player.getInventory().addItem(is);
			
			if(result != null && result.size() > 0) {
				is.setType(Material.GLASS_BOTTLE);
				player.getInventory().addItem(is);
				
				noSpace = true;
				
				break;
			} else {
				bottleCount += initialAmount;
			}
		}
		
		if(bottleCount > 0) {
			int endXP = totalExp - bottleCount * xpPerBottle;
			
			player.setLevel(0);
			player.setExp(0);
			player.giveExp(endXP);
			
			player.sendMessage(ChatColor.GREEN + "Created " + bottleCount +  " XP bottles.");
		}
		
		if(noSpace) { 
			player.sendMessage(ChatColor.RED + "Not enough space in inventory for all XP bottles.");
		}
	}
	
	@EventHandler
	public void onEmeraldXP(PlayerInteractEvent event) {
		// If emerald crafting is not enabled, back out
		if (!emeraldCrafting) {
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
		ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
		if (held == null || !Material.EMERALD.equals(held.getType()) || held.getDurability() != 0) {
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
		// Apply the xp to the player at the cost of an emerald
		event.getPlayer().giveExp(xpPerBottle * 9);
		if (amount == 1) {
			event.getPlayer().getInventory().setItemInMainHand(null);
		}
		else {
			held.setAmount(--amount);
			event.getPlayer().getInventory().setItemInMainHand(held);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void xpBottleEvent(ExpBottleEvent event) {
		if (noExp) {
			ThrownExpBottle bottle = event.getEntity();
			ProjectileSource source = bottle.getShooter();
			if (source instanceof Player) {
				Player shooter = (Player) source;
				shooter.giveExp(xpPerBottle);
				bottle.teleport(shooter);
			}
			// NOTE: For some reason event.getHitBlock() and event.getHitEntity() will always return null
			// and the location of the bottle upon this event's calling can be unexpected, which is why a
			// radius larger than two is necessary because even if the event calls because the bottle hit
			// a player, in some circumstances the location of the bottle still may be 1.8 blocks away,
			// even if the bottle appears to have hit the player's feet or waist. Better to have to sort
			// through a pool of larger entities than allow blatantly obvious collisions to go undetected
			else if (source instanceof BlockProjectileSource) {
				List<Player> nearby = bottle.getNearbyEntities(dispenserXPRadius, dispenserXPRadius + 1, dispenserXPRadius)
						.stream()
						.filter((entity) -> entity instanceof Player)
						.map((entity) -> (Player) entity)
						.collect(Collectors.toList());
				// If no player is found, prevent orbs from spawning
				if (nearby.isEmpty()) {
					event.setShowEffect(false);
					event.setExperience(0);
					return;
				}
				Player closest = null;
				double distance = Double.MAX_VALUE;
				for (Player player : nearby) {
					Location playerLocation = player.getLocation();
					Location bottleLocation = bottle.getLocation();
					// Reduce the importance of slight y level differences if the bottle is above the player
					// so that someone standing on higher ground will not take precedence over someone
					// standing closer, just because the potion is aimed at the latter person's head
					double diffY = playerLocation.getY() - bottleLocation.getY();
					if (diffY < 0) {
						bottleLocation.setY(bottleLocation.getY() + (diffY < -1 ? -1 : diffY));
					}
					double tempDistance = playerLocation.distance(bottleLocation);
					// If there's no other player to compare to, just set this player as the closest
					// or if this player is closer, then set this player as the closest
					if (closest == null || tempDistance < distance) {
						closest = player;
						distance = tempDistance;
					}
				}
				closest.giveExp(xpPerBottle);
				bottle.teleport(closest);
			}
		}
		else {
			event.setExperience(xpPerBottle);
		}
	}
	
	@EventHandler(priority = EventPriority.MONITOR)
	public void xpBottleMonitor(ExpBottleEvent event) {
		if(event.getExperience() != (noExp ? 0 : xpPerBottle)) {
			getLogger().log(Level.INFO, "XP control lost: " + event.getExperience());
		}
	}
	
	@EventHandler
	public void onExpChange(PlayerExpChangeEvent event) {
		if(noExp) {
			event.setAmount(0);
		}
	}
	
	private int computeCurrentXP(Player player) {
		float currentLevel = (float) player.getLevel();
		float progress = player.getExp();
		float a = 1f, b = 6f, c = 0f, x = 2f, y = 7f;
		if(currentLevel > 16 && currentLevel <= 31) {
			a = 2.5f; b = -40.5f; c = 360f; x = 5f; y = -38f;
		} else if(currentLevel >= 32) {
			a = 4.5f; b = -162.5f; c = 2220f; x = 9f; y = -158f;
		}
		return (int) Math.floor(a * currentLevel * currentLevel + b * currentLevel + c + progress * (x * currentLevel + y));
	}
}