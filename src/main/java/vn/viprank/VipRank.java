package vn.viprank;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VipRank - rank mua bang kim cuong: them so luong home va quyen rieng theo rank.
 * Lenh: /rank. Home do EssentialsX quan ly (VipRank cap quyen theo rank).
 */
public class VipRank extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
    private final Map<UUID, String> rankCache = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    private File playersFile;
    private YamlConfiguration players;

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        for (String name : new String[] {"rank"}) {
            PluginCommand c = getCommand(name);
            if (c != null) {
                c.setExecutor(this);
                c.setTabCompleter(this);
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyRank(p);
        }
        getLogger().info("VipRank da bat. So rank: " + rankIds().size());
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof RankMenu) {
                p.closeInventory();
            }
            removeAttachment(p);
            removeNametag(p);
        }
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team t : new ArrayList<>(sb.getTeams())) {
            if (t.getName().startsWith(TEAM_PREFIX)) {
                t.unregister();
            }
        }
    }

    private void loadData() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        playersFile = new File(getDataFolder(), "players.yml");
        players = YamlConfiguration.loadConfiguration(playersFile);
    }

    private void savePlayers() {
        try {
            players.save(playersFile);
        } catch (IOException ex) {
            getLogger().warning("Khong luu duoc players.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ messages

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    /** Lay tin nhan tu config, thay %key% bang gia tri (cap key, value lien tiep). */
    private String msg(String key, String... kv) {
        String s = getConfig().getString("messages." + key, key);
        s = s.replace("%prefix%", getConfig().getString("prefix", ""));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("%" + kv[i] + "%", kv[i + 1]);
        }
        return color(s);
    }

    private void say(CommandSender to, String key, String... kv) {
        to.sendMessage(msg(key, kv));
    }

    // ------------------------------------------------------------ ranks

    private List<String> rankIds() {
        List<String> out = new ArrayList<>();
        ConfigurationSection sec = getConfig().getConfigurationSection("ranks");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                out.add(k.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private String defaultRank() {
        String d = getConfig().getString("default-rank", "member").toLowerCase(Locale.ROOT);
        List<String> ids = rankIds();
        if (ids.contains(d)) {
            return d;
        }
        return ids.isEmpty() ? d : ids.get(0);
    }

    /** Tim ten rank dung hoa/thuong trong config. */
    private String findRank(String input) {
        if (input == null) {
            return null;
        }
        for (String id : rankIds()) {
            if (id.equalsIgnoreCase(input)) {
                return id;
            }
        }
        return null;
    }

    private String rankKey(String id) {
        // Giu nguyen ten khoa goc trong config (co the co chu hoa)
        ConfigurationSection sec = getConfig().getConfigurationSection("ranks");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                if (k.equalsIgnoreCase(id)) {
                    return k;
                }
            }
        }
        return id;
    }

    private int rankIndex(String id) {
        return rankIds().indexOf(id.toLowerCase(Locale.ROOT));
    }

    private String display(String id) {
        return getConfig().getString("ranks." + rankKey(id) + ".display", "&7[" + id + "]");
    }

    private int price(String id) {
        return getConfig().getInt("ranks." + rankKey(id) + ".price", 0);
    }

    private boolean buyable(String id) {
        return getConfig().getBoolean("ranks." + rankKey(id) + ".buyable", true) && price(id) > 0;
    }

    private int homesOf(String id) {
        return getConfig().getInt("ranks." + rankKey(id) + ".homes", 1);
    }

    private String getRank(UUID id) {
        String cached = rankCache.get(id);
        if (cached != null) {
            return cached;
        }
        String r = findRank(players.getString("players." + id + ".rank"));
        if (r == null) {
            r = defaultRank();
        }
        rankCache.put(id, r);
        return r;
    }

    private void setRank(UUID id, String name, String rank) {
        players.set("players." + id + ".rank", rank);
        players.set("players." + id + ".name", name);
        savePlayers();
        rankCache.put(id, rank);
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            applyRank(online);
        }
    }

    // ------------------------------------------------------------ apply (quyen + tab)

    private void removeAttachment(Player p) {
        PermissionAttachment old = attachments.remove(p.getUniqueId());
        if (old != null) {
            try {
                p.removeAttachment(old);
            } catch (IllegalArgumentException ignored) {
                // attachment da bi go
            }
        }
    }

    private void applyRank(Player p) {
        removeAttachment(p);
        String rank = getRank(p.getUniqueId());
        PermissionAttachment att = p.addAttachment(this);

        // Rank cao ke thua quyen cua cac rank thap hon (neu bat inherit)
        List<String> ids = rankIds();
        int idx = ids.indexOf(rank);
        boolean inherit = getConfig().getBoolean("inherit-permissions", true);
        for (int i = 0; i < ids.size(); i++) {
            if (i == idx || (inherit && i < idx)) {
                for (String perm : getConfig().getStringList("ranks." + rankKey(ids.get(i)) + ".permissions")) {
                    if (perm == null || perm.isEmpty()) {
                        continue;
                    }
                    if (perm.startsWith("-")) {
                        att.setPermission(perm.substring(1), false);
                    } else {
                        att.setPermission(perm, true);
                    }
                }
            }
        }
        // Home cua EssentialsX: moi rank = 1 "set" trong sethome-multiple (ten set = ten rank)
        if (getConfig().getBoolean("essentials-homes", true)) {
            att.setPermission("essentials.sethome", true);
            att.setPermission("essentials.sethome.multiple", true);
            for (int i = 0; i < ids.size(); i++) {
                // chi bat dung set cua rank hien tai, tat cac set con lai de khong bi lan
                att.setPermission("essentials.sethome.multiple." + ids.get(i), i == idx);
            }
        }
        attachments.put(p.getUniqueId(), att);

        if (getConfig().getBoolean("tab.enabled", true)) {
            p.playerListName(legacy.deserialize(display(rank) + " &f" + p.getName()));
        }
        applyNametag(p, rank);
    }

    // ------------------------------------------------------------ tag tren dau (nametag)

    private static final String TEAM_PREFIX = "viprank_";

    /** Ten team: rank cao hon -> so nho hon, de xep len dau danh sach tab. */
    private String teamName(String rank) {
        int total = rankIds().size();
        int idx = Math.max(0, rankIndex(rank));
        return TEAM_PREFIX + String.format("%02d", Math.max(0, total - idx));
    }

    private void applyNametag(Player p, String rank) {
        if (!getConfig().getBoolean("nametag.enabled", true)) {
            removeNametag(p);
            return;
        }
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        removeNametag(p);
        String tn = teamName(rank);
        Team team = sb.getTeam(tn);
        if (team == null) {
            team = sb.registerNewTeam(tn);
        }
        team.prefix(legacy.deserialize(display(rank) + " "));
        team.addEntry(p.getName());
    }

    private void removeNametag(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team t : sb.getTeams()) {
            if (t.getName().startsWith(TEAM_PREFIX) && t.hasEntry(p.getName())) {
                t.removeEntry(p.getName());
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        applyRank(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        removeAttachment(e.getPlayer());
        removeNametag(e.getPlayer());
        rankCache.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        if (!getConfig().getBoolean("chat.enabled", true)) {
            return;
        }
        final Component prefix = legacy.deserialize(display(getRank(e.getPlayer().getUniqueId())) + " ");
        e.renderer((source, sourceDisplayName, message, viewer) ->
                prefix.append(sourceDisplayName).append(Component.text(": ")).append(message));
    }

    // ------------------------------------------------------------ currency (kim cuong)

    private Material currency() {
        Material m = Material.matchMaterial(getConfig().getString("currency.material", "DIAMOND"));
        return m == null ? Material.DIAMOND : m;
    }

    private String currencyName() {
        return getConfig().getString("currency.name", "kim cuong");
    }

    private int count(Player p, Material m) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() == m) {
                n += it.getAmount();
            }
        }
        return n;
    }

    /** So kim cuong can tra de len rank target (da tinh phan chenh lech neu bat). */
    private int costFor(String current, String target) {
        int cost = price(target);
        if (getConfig().getBoolean("pay-difference", true)) {
            cost = Math.max(0, cost - price(current));
        }
        return cost;
    }

    // ---- vi kim cuong (tich dan)

    private int getBalance(UUID id) {
        return players.getInt("players." + id + ".balance", 0);
    }

    private void setBalance(UUID id, int value) {
        players.set("players." + id + ".balance", Math.max(0, value));
        savePlayers();
    }

    private void deposit(Player p, int amount) {
        Material cur = currency();
        int n = Math.min(count(p, cur), amount);
        if (n <= 0) {
            say(p, "nothing-to-deposit", "currency", currencyName());
            return;
        }
        p.getInventory().removeItem(new ItemStack(cur, n));
        int total = getBalance(p.getUniqueId()) + n;
        setBalance(p.getUniqueId(), total);
        say(p, "deposited", "amount", String.valueOf(n), "balance", String.valueOf(total),
                "currency", currencyName());
    }

    private void withdrawAll(Player p) {
        int bal = getBalance(p.getUniqueId());
        if (bal <= 0) {
            say(p, "nothing-to-withdraw");
            return;
        }
        int left = 0;
        for (ItemStack rest : p.getInventory().addItem(new ItemStack(currency(), bal)).values()) {
            left += rest.getAmount();
        }
        setBalance(p.getUniqueId(), left); // phan khong vua tui do van giu trong vi
        say(p, "withdrawn", "amount", String.valueOf(bal - left), "currency", currencyName());
    }

    // ---- menu GUI

    private static final int[] MENU_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};

    private static class RankMenu implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, String> slots = new HashMap<>();
        private int balanceSlot;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack menuItem(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m, 1);
        it.editMeta(meta -> {
            meta.displayName(noItalic(legacy.deserialize(name)));
            List<Component> lines = new ArrayList<>();
            for (String s : lore) {
                lines.add(noItalic(legacy.deserialize(s)));
            }
            meta.lore(lines);
        });
        return it;
    }

    private String bar(int have, int need) {
        int len = 10;
        int filled = need <= 0 ? len : Math.min(len, (int) ((long) have * len / need));
        StringBuilder sb = new StringBuilder("&a");
        for (int i = 0; i < len; i++) {
            if (i == filled) {
                sb.append("&7");
            }
            sb.append("■");
        }
        return sb.toString();
    }

    private void openMenu(Player p) {
        int n = Math.min(rankIds().size(), MENU_SLOTS.length);
        int size = n <= 7 ? 27 : 45;
        RankMenu holder = new RankMenu();
        holder.balanceSlot = size - 5;
        holder.inventory = Bukkit.createInventory(holder, size,
                legacy.deserialize(getConfig().getString("gui.title", "&8Rank")));
        fillMenu(p, holder);
        p.openInventory(holder.inventory);
    }

    private void fillMenu(Player p, RankMenu holder) {
        Inventory inv = holder.inventory;
        inv.clear();
        holder.slots.clear();
        UUID id = p.getUniqueId();
        Material cur = currency();
        int bal = getBalance(id);
        int inPack = count(p, cur);
        int total = bal + inPack;
        String current = getRank(id);
        String cname = currencyName();
        List<String> ids = rankIds();

        for (int i = 0; i < ids.size() && i < MENU_SLOTS.length; i++) {
            String r = ids.get(i);
            int cost = rankIndex(r) > rankIndex(current) ? costFor(current, r) : 0;
            String status;
            if (rankIndex(r) <= rankIndex(current)) {
                status = getConfig().getString("gui.status-owned", "&aDa so huu");
            } else if (!buyable(r)) {
                status = getConfig().getString("gui.status-unbuyable", "&cKhong ban");
            } else if (total >= cost) {
                status = getConfig().getString("gui.status-buy", "&eBam de mua");
            } else {
                status = getConfig().getString("gui.status-need", "&cThieu %missing%")
                        .replace("%missing%", String.valueOf(cost - total));
            }
            List<String> lore = new ArrayList<>();
            for (String line : getConfig().getStringList("gui.rank-lore")) {
                lore.add(line
                        .replace("%price%", String.valueOf(price(r)))
                        .replace("%cost%", String.valueOf(cost))
                        .replace("%have%", String.valueOf(total))
                        .replace("%homes%", String.valueOf(homesOf(r)))
                        .replace("%bar%", bar(total, cost))
                        .replace("%currency%", cname)
                        .replace("%status%", status));
            }
            Material icon = Material.matchMaterial(getConfig().getString("ranks." + rankKey(r) + ".icon", "PAPER"));
            inv.setItem(MENU_SLOTS[i], menuItem(icon == null ? Material.PAPER : icon, display(r), lore));
            holder.slots.put(MENU_SLOTS[i], r);
        }

        List<String> blore = new ArrayList<>();
        for (String line : getConfig().getStringList("gui.balance-lore")) {
            blore.add(line.replace("%balance%", String.valueOf(bal))
                    .replace("%inv%", String.valueOf(inPack))
                    .replace("%currency%", cname));
        }
        inv.setItem(holder.balanceSlot, menuItem(cur,
                getConfig().getString("gui.balance-name", "&bKim cuong da tich: &f%balance%")
                        .replace("%balance%", String.valueOf(bal)),
                blore));
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof RankMenu)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player) || e.getClickedInventory() != e.getInventory()) {
            return;
        }
        RankMenu holder = (RankMenu) e.getInventory().getHolder();
        Player p = (Player) e.getWhoClicked();
        int slot = e.getRawSlot();
        if (slot == holder.balanceSlot) {
            if (e.isShiftClick() && e.isRightClick()) {
                withdrawAll(p);
            } else if (e.isRightClick()) {
                deposit(p, 64);
            } else {
                deposit(p, Integer.MAX_VALUE);
            }
            fillMenu(p, holder);
            return;
        }
        String rank = holder.slots.get(slot);
        if (rank != null) {
            buy(p, rank);
            fillMenu(p, holder);
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof RankMenu) {
            e.setCancelled(true);
        }
    }

    private void buy(Player p, String input) {
        String target = findRank(input);
        if (target == null) {
            say(p, "unknown-rank", "rank", input);
            return;
        }
        String current = getRank(p.getUniqueId());
        if (rankIndex(target) <= rankIndex(current)) {
            say(p, "already-have", "rank", display(target));
            return;
        }
        if (!buyable(target)) {
            say(p, "not-buyable", "rank", display(target));
            return;
        }
        int cost = costFor(current, target);
        Material cur = currency();
        UUID uid = p.getUniqueId();
        int bal = getBalance(uid);
        int inPack = count(p, cur);
        if (bal + inPack < cost) {
            say(p, "not-enough", "need", String.valueOf(cost), "have", String.valueOf(bal + inPack),
                    "currency", currencyName());
            return;
        }
        // Tru tu vi (kim cuong da tich) truoc, thieu thi lay them trong tui do
        int fromWallet = Math.min(bal, cost);
        int fromPack = cost - fromWallet;
        if (fromWallet > 0) {
            setBalance(uid, bal - fromWallet);
        }
        if (fromPack > 0) {
            p.getInventory().removeItem(new ItemStack(cur, fromPack));
        }
        setRank(p.getUniqueId(), p.getName(), target);
        say(p, "bought", "rank", display(target), "cost", String.valueOf(cost),
                "currency", currencyName(), "homes", String.valueOf(homesOf(target)));
        if (getConfig().getBoolean("broadcast-purchase", true)) {
            String announce = msg("broadcast", "player", p.getName(), "rank", display(target));
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.sendMessage(announce);
            }
        }
    }

    // ------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return rankCommand(sender, args);
    }

    private boolean rankCommand(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list":
                showList(sender);
                return true;
            case "buy":
                if (!(sender instanceof Player)) {
                    say(sender, "only-player");
                    return true;
                }
                if (!sender.hasPermission("viprank.buy")) {
                    say(sender, "no-permission");
                    return true;
                }
                if (args.length < 2) {
                    openMenu((Player) sender);   // /rank buy -> mo bang menu rank
                } else {
                    buy((Player) sender, args[1]);
                }
                return true;
            case "info":
                showInfo(sender, args.length > 1 ? args[1] : null);
                return true;
            case "set":
                if (!sender.hasPermission("viprank.admin")) {
                    say(sender, "no-permission");
                    return true;
                }
                if (args.length < 3) {
                    say(sender, "usage-set");
                    return true;
                }
                adminSet(sender, args[1], args[2]);
                return true;
            case "reload":
                if (!sender.hasPermission("viprank.admin")) {
                    say(sender, "no-permission");
                    return true;
                }
                reloadConfig();
                rankCache.clear();
                for (Player online : Bukkit.getOnlinePlayers()) {
                    applyRank(online);
                }
                say(sender, "reloaded");
                return true;
            default:
                say(sender, "usage-rank");
                return true;
        }
    }

    private void showList(CommandSender sender) {
        say(sender, "list-header");
        for (String id : rankIds()) {
            String p = price(id) > 0 ? price(id) + " " + currencyName() : "mien phi";
            sender.sendMessage(msg("list-line", "rank", display(id), "price", p,
                    "homes", String.valueOf(homesOf(id))));
        }
    }

    private void showInfo(CommandSender sender, String who) {
        UUID id;
        String name;
        if (who == null) {
            if (!(sender instanceof Player)) {
                say(sender, "only-player");
                return;
            }
            id = ((Player) sender).getUniqueId();
            name = sender.getName();
        } else {
            if (!sender.hasPermission("viprank.admin") && !who.equalsIgnoreCase(sender.getName())) {
                say(sender, "no-permission");
                return;
            }
            OfflinePlayer off = Bukkit.getOfflinePlayer(who);
            if (!off.hasPlayedBefore() && !off.isOnline()) {
                say(sender, "player-not-found", "player", who);
                return;
            }
            id = off.getUniqueId();
            name = off.getName() == null ? who : off.getName();
        }
        String rank = getRank(id);
        say(sender, "info", "player", name, "rank", display(rank), "homes", String.valueOf(homesOf(rank)));
    }

    private void adminSet(CommandSender sender, String who, String rankInput) {
        String rank = findRank(rankInput);
        if (rank == null) {
            say(sender, "unknown-rank", "rank", rankInput);
            return;
        }
        OfflinePlayer off = Bukkit.getOfflinePlayer(who);
        if (!off.hasPlayedBefore() && !off.isOnline()) {
            say(sender, "player-not-found", "player", who);
            return;
        }
        String name = off.getName() == null ? who : off.getName();
        setRank(off.getUniqueId(), name, rank);
        say(sender, "rank-set", "player", name, "rank", display(rank));
    }

    // ------------------------------------------------------------ tab

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("rank")) {
            if (args.length == 1) {
                for (String s : new String[] {"list", "buy", "info", "set", "reload"}) {
                    if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                        out.add(s);
                    }
                }
            } else if (args.length == 2 && args[0].equalsIgnoreCase("buy")) {
                out.addAll(matching(rankIds(), args[1]));
            } else if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("info"))) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                out.addAll(matching(names, args[1]));
            } else if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
                out.addAll(matching(rankIds(), args[2]));
            }
        }
        return out;
    }

    private static List<String> matching(List<String> all, String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix.toLowerCase(Locale.ROOT);
        for (String s : all) {
            if (s.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(s);
            }
        }
        return out;
    }
}
