package com.civclassic.oldenchanting;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.v1_12_R1.inventory.CraftInventoryView;
import org.bukkit.craftbukkit.v1_12_R1.inventory.CraftItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExpEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.java.JavaPlugin;
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
	private int xpPerBottle;
	private boolean infiniteEnchant;
	
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
		xpPerBottle = getConfig().getInt("exp_per_bottle", 10);
		infiniteEnchant = getConfig().getBoolean("infinite_enchant", true);

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
		if(event.getInventory().getRepairCost() > 39) {
			event.getInventory().setRepairCost(39);
		}
		/* Legacy code from 1.10
		ItemStack result = event.getResult();
		if(result != null && result.getType() != Material.AIR) {
			net.minecraft.server.v1_12_R1.ItemStack is = CraftItemStack.asNMSCopy(result);
			if(is == null) return;
			if(is.getRepairCost() > 37) {
				is.setRepairCost(37);
				event.setResult(CraftItemStack.asBukkitCopy(is));
			}
		}
		*/
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
	
	@EventHandler(priority = EventPriority.HIGHEST)
	public void xpBottleEvent(ExpBottleEvent event) {
		if(noExp) {
			ProjectileSource source = event.getEntity().getShooter();
			if(source instanceof Player) {
				Player shooter = (Player) source;
				shooter.giveExp(xpPerBottle);
			}
		} else {
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