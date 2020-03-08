package pl.extollite.hungergames.listener;

import cn.nukkit.block.Block;
import cn.nukkit.block.BlockIds;
import cn.nukkit.blockentity.ItemFrame;
import cn.nukkit.blockentity.Sign;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.impl.EntityLiving;
import cn.nukkit.entity.misc.ArmorStand;
import cn.nukkit.event.*;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.block.ItemFrameDropItemEvent;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.entity.EntitySpawnEvent;
import cn.nukkit.event.player.*;
import cn.nukkit.inventory.PlayerInventory;
import cn.nukkit.item.Item;
import cn.nukkit.item.ItemIds;
import cn.nukkit.level.Location;
import cn.nukkit.level.Sound;
import cn.nukkit.player.Player;
import cn.nukkit.utils.Identifier;
import pl.extollite.hungergames.HG;
import pl.extollite.hungergames.HGUtils.HGUtils;
import pl.extollite.hungergames.data.ConfigData;
import pl.extollite.hungergames.data.Language;
import pl.extollite.hungergames.data.Leaderboard;
import pl.extollite.hungergames.data.PlayerData;
import pl.extollite.hungergames.events.ChestOpenEvent;
import pl.extollite.hungergames.form.SpectatorWindow;
import pl.extollite.hungergames.game.Game;
import pl.extollite.hungergames.game.Status;
import pl.extollite.hungergames.manager.KillManager;
import pl.extollite.hungergames.manager.Manager;
import pl.extollite.hungergames.manager.PlayerManager;

import java.util.UUID;

/**
 * Internal event listener
 */
public class GameListener implements Listener {

    private HG plugin;
    private Language lang;

    private PlayerManager playerManager;
    private Leaderboard leaderboard;

    public GameListener() {
        this.plugin = HG.getInstance();
        this.lang = plugin.getLanguage();
        this.playerManager = plugin.getPlayerManager();
        this.leaderboard = plugin.getLeaderboard();
    }

    private void dropInv(Player p) {
        PlayerInventory inv = p.getInventory();
        Location l = p.getLocation();
        for (Item i : inv.getContents().values()) {
            if (i != null && !i.getId().equals(BlockIds.AIR)) {
                assert l.getLevel() != null;
                l.getLevel().dropItem(l.getPosition(), i);
            }
        }
        for (Item i : inv.getArmorContents()) {
            if (i != null && !i.getId().equals(BlockIds.AIR)) {
                assert l.getLevel() != null;
                l.getLevel().dropItem(l.getPosition(), i);
            }
        }
        Item i = inv.getOffHand();
        if (i != null && !i.getId().equals(BlockIds.AIR)) {
            assert l.getLevel() != null;
            l.getLevel().dropItem(l.getPosition(), i);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onAttack(EntityDamageByEntityEvent event) {
        Entity defender = event.getEntity();
        Entity damager = event.getDamager();

        if (defender instanceof Player) {
            Player player = (Player) defender;
            PlayerData pd = playerManager.getPlayerData(player);

            if (pd != null) {
                Game game = pd.getGame();

                if (game.getStatus() != Status.RUNNING) {
                    event.setCancelled(true);
                } else if (event.getFinalDamage() >= player.getHealth()) {
                    if (hasTotem(player)) return;
                    event.setCancelled(true);
                    processDeath(player, game, event);
                }
            }
        }

    }

    @EventHandler // Prevent players breaking item frames
    private void onBreakItemFrame(ItemFrameDropItemEvent event) {
        handleItemFrame(event.getItemFrame(), event);
    }

    private void handleItemFrame(ItemFrame itemFrame, Event event) {
        Game game = Manager.isInRegion(Location.from(itemFrame.getPosition(), itemFrame.getLevel()));
        if (game != null) {
            switch (game.getStatus()) {
                case RUNNING:
                case BEGINNING:
                case COUNTDOWN:
                    ((Cancellable) event).setCancelled();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onDeathByOther(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            final Player player = ((Player) event.getEntity());
            if (event instanceof EntityDamageByEntityEvent) return;
            PlayerData pd = playerManager.getPlayerData(player);
            if (pd != null) {
                if (event.getFinalDamage() >= player.getHealth()) {
                    if (hasTotem(player)) return;
                    event.setCancelled(true);
                    processDeath(player, pd.getGame(), event);
                }
            }
        }
    }

    @SuppressWarnings("ConstantConditions")
    private boolean hasTotem(Player player) {
        PlayerInventory inv = player.getInventory();
        if (inv.getItemInHand() != null && inv.getItemInHand().getId().equals(ItemIds.TOTEM)) return true;
        return inv.getOffHand() != null && inv.getOffHand().getId().equals(ItemIds.TOTEM);
    }

    private void processDeath(Player player, Game game, EntityDamageEvent event) {
        dropInv(player);
        player.setHealth(20);
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
            game.msgAll(HGUtils.colorize(KillManager.getDeathMessage(event)));
            leaderboard.addStat(player, Leaderboard.Stats.DEATHS);
            leaderboard.addStat(player, Leaderboard.Stats.GAMES);

            for (Player p : game.getPlayers()) {
                p.getLevel().addSound(p.getPosition(), Sound.RANDOM_TOAST, 5, 1, game.getPlayers());
            }

            game.leave(player, true);
            game.runCommands(Game.CommandType.DEATH, player);

        }, 1);

    }

    @EventHandler
    private void onSprint(PlayerFoodLevelChangeEvent event) {
        Player p = event.getPlayer();
        if (playerManager.hasPlayerData(p)) {
            Status st = playerManager.getPlayerData(p).getGame().getStatus();
            if (st == Status.WAITING || st == Status.COUNTDOWN || st == Status.FINAL) {
                event.setFoodLevel(1);
                event.setCancelled(true);
            }
        }
    }

    private double angle(double d, double e, double f, double g) {
        //Vector differences
        int x = (int) (f - d);
        int z = (int) (g - e);

        return Math.atan2(x, z);
    }

    @EventHandler
    private void onChestOpen(ChestOpenEvent event) {
        Block b = event.getBlock();
        Game g = event.getGame();
        if (!g.isLoggedChest(Location.from(b.getPosition(), b.getLevel()))) {
            Manager.fillChests(b, g, event.isBonus());
            g.addGameChest(Location.from(b.getPosition(), b.getLevel()));
        }
    }

    @EventHandler
    private void onChestUse(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (event.getAction() == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK && playerManager.hasPlayerData(p)) {
            Block block = event.getBlock();
            assert block != null;
            PlayerData pd = playerManager.getPlayerData(p);
            if (isChest(block)) {
                plugin.getServer().getPluginManager().callEvent(new ChestOpenEvent(pd.getGame(), block, false));
            }
        }
    }

    @EventHandler
    private void onItemUseAttempt(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (playerManager.hasSpectatorData(p)) {
            event.setCancelled(true);
            if (isSpectatorCompass(event)) {
                handleSpectatorCompass(p);
                return;
            }
        }
        if (event.getAction() != PlayerInteractEvent.Action.PHYSICAL && playerManager.hasPlayerData(p)) {
            Status st = playerManager.getPlayerData(p).getGame().getStatus();
            if (st == Status.WAITING || st == Status.COUNTDOWN || st == Status.FINAL) {
                event.setCancelled(true);
                HGUtils.sendMessage(p, lang.getListener_no_interact());
            }
        }
    }

    private boolean isSpectatorCompass(PlayerInteractEvent event) {
        PlayerInteractEvent.Action action = event.getAction();
        Player player = event.getPlayer();
        if (action != PlayerInteractEvent.Action.LEFT_CLICK_BLOCK && action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK)
            return false;
        if (!playerManager.hasSpectatorData(player)) return false;

        Item item = event.getItem();
        if (item == null || item.getId().equals(ItemIds.COMPASS)) return false;
        return item.getCustomName() != null && item.getCustomName().equalsIgnoreCase(HGUtils.colorize(lang.getSpectator_compass()));

    }

    private void handleSpectatorCompass(Player player) {
        Game game = playerManager.getSpectatorData(player).getGame();
        player.showFormWindow(new SpectatorWindow(game));
    }

    @EventHandler
    private void onPlayerClickLobby(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (event.getAction().equals(PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) || event.getAction().equals(PlayerInteractEvent.Action.LEFT_CLICK_BLOCK)) {
            Block b = event.getBlock();
            if(!(b.getLevel().getBlockEntity(b.getPosition()) instanceof Sign))
                return;
            Sign sign = (Sign)b.getLevel().getBlockEntity(b.getPosition());
            assert sign != null;
            if (sign.getText().length == 4 && sign.getText()[0].equals(HGUtils.colorize(lang.getLine_1()))) {
                Game game = Manager.getGame(sign.getText()[3].split(" ")[1]);
                if (game == null) {
                    HGUtils.sendMessage(p, lang.getCmd_delete_noexist());
                } else {
                    if (p.getInventory().getItemInHand().getId().equals(BlockIds.AIR)) {
                        game.join(p);
                    } else {
                        HGUtils.sendMessage(p, lang.getListener_sign_click_hand());
                    }
                }
            }
        }
    }

    @EventHandler
    private void blockPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        Block b = event.getBlock();
        Location location = Location.from(b.getPosition(), b.getLevel());
        Game game = Manager.isInRegion(location);
        if (game != null) {
            if (ConfigData.breakblocks && playerManager.hasPlayerData(p)) {
                Game g = playerManager.getPlayerData(p).getGame();
                if (g.getStatus() == Status.RUNNING || g.getStatus() == Status.BEGINNING || g.getStatus() == Status.FINAL) {
                    if (!ConfigData.blocks.contains(b.getId()) && !ConfigData.blocks.contains("ALL")) {
                        HGUtils.sendMessage(p, lang.getListener_no_edit_block());
                        event.setCancelled(true);
                    } else {
                        if (isChest(b)) {
                            g.addPlayerChest(location);
                        }
                    }
                } else {
                    HGUtils.sendMessage(p, lang.getListener_not_running());
                    event.setCancelled(true);
                }
            } else {
                if (p.hasPermission("hg.create")) {
                    Game g = Manager.getGame(location);
                    return;
                }
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    private void blockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block b = event.getBlock();
        Location location = Location.from(b.getPosition(), b.getLevel());
        Game game = Manager.isInRegion(location);
        if (game != null) {
            if (ConfigData.breakblocks && playerManager.hasPlayerData(player)) {
                Game g = playerManager.getPlayerData(player).getGame();
                if (g.getStatus() == Status.RUNNING || !ConfigData.protectCooldown) {
                    if (!ConfigData.blocks.contains(b.getId()) && !ConfigData.blocks.contains(Identifier.EMPTY)) {
                        HGUtils.sendMessage(player, lang.getListener_no_edit_block());
                        event.setCancelled(true);
                    } else {
                        if (isChest(b)) {
                            g.removeGameChest(location);
                            g.removePlayerChest(location);
                        }
                    }
                } else {
                    HGUtils.sendMessage(player, lang.getListener_not_running());
                    event.setCancelled(true);
                }
            } else {
                if (!playerManager.hasPlayerData(player) && player.hasPermission("hg.create")) {
                    Status status = game.getStatus();
                    switch (status) {
                        case BEGINNING:
                        case RUNNING:
                            game.removeGameChest(location);
                        default:
                            return;
                    }
                }
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    private void onBucketEmpty(PlayerBucketEmptyEvent event) {
        handleBucketEvent(event, false);
    }

    @EventHandler
    private void onBucketDrain(PlayerBucketFillEvent event) {
        handleBucketEvent(event, true);
    }

    private void handleBucketEvent(PlayerBucketEvent event, boolean fill) {
        Block block = event.getBlockClicked();
        Player player = event.getPlayer();
        final boolean WATER = block.getId().equals(BlockIds.WATER) && ConfigData.blocks.contains(BlockIds.WATER);
        final boolean LAVA = block.getId().equals(BlockIds.LAVA) && ConfigData.blocks.contains(BlockIds.LAVA);
        Location loc = Location.from(block.getPosition(), block.getLevel());
        Game game = Manager.isInRegion(loc);
        if (game != null) {
            if (ConfigData.breakblocks && playerManager.hasPlayerData(player)) {
                if (game.getStatus() == Status.RUNNING || !ConfigData.protectCooldown) {
                    if (!WATER && !LAVA && !ConfigData.blocks.contains(Identifier.EMPTY) && !ConfigData.blocks.contains(block.getId())) {
                        HGUtils.sendMessage(player, lang.getListener_no_edit_block());
                        event.setCancelled(true);
                    }
                } else {
                    HGUtils.sendMessage(player, lang.getListener_not_running());
                    event.setCancelled(true);
                }
            } else {
                if (playerManager.hasPlayerData(player) || !player.hasPermission("hg.create")) {
                    event.setCancelled(true);
                }
            }
        }
    }

    private boolean isChest(Block block) {
        return BlockIds.CHEST.equals(block.getId()) || BlockIds.SHULKER_BOX.equals(block.getId()) || BlockIds.TRAPPED_CHEST.equals(block.getId()) || BlockIds.BARREL.equals(block.getId());
    }


    @EventHandler
    private void onTrample(PlayerInteractEvent event) {
        if (!ConfigData.preventtrample) return;
        Player p = event.getPlayer();
        Game game = Manager.isInRegion(p.getLocation());
        if (game != null) {
            if (event.getAction() == PlayerInteractEvent.Action.PHYSICAL) {
                assert event.getBlock() != null;
                if (event.getBlock().getId().equals(BlockIds.FARMLAND)) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    private void onDrop(PlayerDropItemEvent event) {
        Player p = event.getPlayer();
        PlayerData pd = playerManager.getPlayerData(p);
        if (pd != null) {
            if (pd.getGame().getStatus() == Status.WAITING) {
                event.setCancelled(true);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler
    private void onSpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player)) {
            Game g = Manager.isInRegion(event.getLocation());
            if (g != null) {
                if (entity instanceof EntityLiving) {
                    if (g.getStatus() != Status.RUNNING) {
                        event.setCancelled(true);
                        return;
                    }
                }
                if (entity instanceof ItemFrame || entity instanceof ArmorStand) return;
                g.getBound().addEntity(entity);
            }
        }
    }

 /*   @EventHandler
	private void onCreatureSpawn(CreatureSpawnEvent event){

		EntityType<?> entity = event.getEntityType();
		if (!(entity.equals(EntityTypes.PLAYER))) {
			Game g = Manager.isInRegion(event.getLocation());
			if (g != null) {
				CreatureSpawnEvent.SpawnReason reason = event.getReason();
				if (reason != CreatureSpawnEvent.SpawnReason.CUSTOM && reason != CreatureSpawnEvent.SpawnReason.EGG) {
					event.setCancelled(true);
					return;
				}
				entity.setPersistent(false);
				if (entity instanceof ItemFrame || entity instanceof ArmorStand) return;
				g.getBound().addEntity(entity.ge);
			}
		}
	}*/

    @EventHandler
    private void onLogout(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (playerManager.hasPlayerData(player)) {
            playerManager.getPlayerData(player).getGame().leave(player, false);
        }
        if (playerManager.hasSpectatorData(player)) {
            playerManager.getSpectatorData(player).getGame().leaveSpectate(player);
        }
    }

    @EventHandler
    private void onChat(PlayerChatEvent event) {
        if (!ConfigData.spectateChat) {
            Player spectator = event.getPlayer();
            if (playerManager.hasSpectatorData(spectator)) {
                PlayerData data = playerManager.getSpectatorData(spectator);
                Game game = data.getGame();
                for (Player player : game.getPlayers()) {
                    event.getRecipients().remove(player);
                }
            }
        }
    }

    @EventHandler
    private void onTeleportIntoArena(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location location = event.getTo();
        for (Game game : plugin.getGames()) {
            if (game.isInRegion(location) && game.getStatus() == Status.RUNNING) {
                if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL && !game.getPlayers().contains(player) && !game.getSpectators().contains(player)) {
                    event.setCancelled(true);
                }
            }
        }
    }

}