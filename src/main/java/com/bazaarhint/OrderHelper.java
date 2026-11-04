package com.bazaarhint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 바자 주문 갱신 도우미 (표시 + 클릭 한 번 = 명령 한 번. 자동 취소/재등록은 하지 않음)
 *  - 내 주문은 '주문 관리' 창을 열 때와 채팅 메시지(주문 생성/체결)로 파악합니다.
 *  - 대시보드(/api/quotes)의 최고 구매 주문/최저 판매 주문과 비교해 앞지름을 감지하면 채팅에 버튼을 띄웁니다.
 *      [바자 열기] -> /bz <아이템>   (바자 검색 화면만 열어 줌)
 *      [새 가격 복사] -> 클립보드에 새 가격 (가격 입력 창에서 Ctrl+V)
 */
public final class OrderHelper {
    record Order(boolean buy, String name, double price) {}

    static volatile List<Order> orders = List.of();
    static volatile Map<String, double[]> quotes = Map.of();  // 소문자 이름 -> [즉시 구매가, 즉시 판매가]
    static volatile Set<Integer> outbidSlots = Set.of();      // 주문 관리 창에서 갱신 필요한 칸
    static volatile int outdatedCount = 0;
    static final Map<String, Long> notified = new ConcurrentHashMap<>();
    static long lastScan = 0;

    static final Pattern SETUP = Pattern.compile("\\[Bazaar\\] (Buy Order|Sell Offer) Setup! ([\\d,]+)x (.+?) for ([\\d,.]+) coins");
    static final Pattern FILLED = Pattern.compile("\\[Bazaar\\] Your (Buy Order|Sell Offer) for ([\\d,]+)x (.+?) was filled");
    static final Pattern UNIT = Pattern.compile("Price per unit: ([\\d,.]+) coins");
    static final Pattern TITLE = Pattern.compile("^(BUY|SELL)\\s+(.+)$");

    private OrderHelper() {}

    static void init() {
        ClientReceiveMessageEvents.GAME.register(OrderHelper::onChat);
    }

    static String clean(String s) {
        return s.replaceAll("§.", "").trim();
    }

    static double num(String s) {
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%,.1f", v);
    }

    // ---------------------------------------------------------------- 내 주문 파악
    static void onChat(Component message, boolean overlay) {
        if (overlay) return;
        String s = clean(message.getString());
        Matcher m = SETUP.matcher(s);
        if (m.find()) {
            double amount = num(m.group(2)), total = num(m.group(4));
            if (amount > 0) {
                List<Order> l = new ArrayList<>(orders);
                l.add(new Order(m.group(1).startsWith("Buy"), m.group(3).trim(), Math.round(total / amount * 10) / 10.0));
                orders = l;
            }
            return;
        }
        m = FILLED.matcher(s);
        if (m.find()) {
            boolean buy = m.group(1).startsWith("Buy");
            String name = m.group(3).trim();
            List<Order> l = new ArrayList<>(orders);
            for (int i = 0; i < l.size(); i++) {
                if (l.get(i).buy() == buy && l.get(i).name().equalsIgnoreCase(name)) {
                    l.remove(i);
                    break;
                }
            }
            orders = l;
        }
    }

    /** '주문 관리' 창의 상품 칸에서 내 주문(가격/종류)을 읽어 목록을 새로 맞춥니다 (1초에 한 번). */
    static void scan(ChestMenu menu) {
        long now = System.currentTimeMillis();
        if (now - lastScan < 1000) return;
        lastScan = now;
        List<Order> list = new ArrayList<>();
        Map<Integer, Order> bySlot = new HashMap<>();
        boolean any = false;
        int count = Math.min(menu.getRowCount() * 9, menu.slots.size());
        for (int i = 0; i < count; i++) {
            ItemStack st = menu.slots.get(i).getItem();
            if (st.isEmpty()) continue;
            any = true;
            Matcher t = TITLE.matcher(clean(st.getHoverName().getString()));
            ItemLore lore = st.get(DataComponents.LORE);
            if (!t.find() || lore == null) continue;
            double price = 0;
            boolean full = false;
            for (Component c : lore.lines()) {
                String l = clean(c.getString());
                Matcher u = UNIT.matcher(l);
                if (u.find()) price = num(u.group(1));
                if (l.startsWith("Filled") && l.contains("100%")) full = true;
            }
            if (price <= 0 || full) continue;
            Order o = new Order(t.group(1).equals("BUY"), t.group(2).trim(), price);
            list.add(o);
            bySlot.put(i, o);
        }
        if (!any) return;  // 아직 내용이 도착하지 않음
        orders = list;
        Set<Integer> out = new HashSet<>();
        for (Map.Entry<Integer, Order> e : bySlot.entrySet()) {
            if (!Double.isNaN(better(e.getValue()))) out.add(e.getKey());
        }
        outbidSlots = out;
    }

    static void drawOutbid(GuiGraphicsExtractor g, ChestMenu menu, int left, int top) {
        for (int idx : outbidSlots) {
            if (idx >= menu.slots.size()) continue;
            Slot s = menu.slots.get(idx);
            int x = left + s.x, y = top + s.y;
            g.fill(x, y, x + 16, y + 16, 0x55FF4040);
            g.outline(x - 1, y - 1, 18, 18, 0xFFFF5555);
        }
    }

    // ---------------------------------------------------------------- 앞지름 감지
    /** 앞지른 주문이 있으면 새로 걸 가격, 없으면 NaN. 구매: 최고 구매 주문이 내 가격보다 높음 / 판매: 최저 판매 주문이 내 가격보다 낮음. */
    static double better(Order o) {
        double[] q = quotes.get(o.name().toLowerCase(Locale.ROOT));
        if (q == null) return Double.NaN;
        double ask = q[0], bid = q[1];
        if (o.buy() && bid > o.price() + 0.05) return Math.round((bid + 0.1) * 10) / 10.0;
        if (!o.buy() && ask < o.price() - 0.05) return Math.round((ask - 0.1) * 10) / 10.0;
        return Double.NaN;
    }

    static void pollQuotes(HttpClient http) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(BazaarHintClient.BASE + "/api/quotes"))
                    .timeout(Duration.ofSeconds(8)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return;
            JsonObject q = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonObject("q");
            Map<String, double[]> map = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : q.entrySet()) {
                JsonArray a = e.getValue().getAsJsonArray();
                map.put(e.getKey().toLowerCase(Locale.ROOT), new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble()});
            }
            quotes = map;
            check();
        } catch (Exception ignored) {
            // 대시보드가 꺼져 있으면 조용히 건너뜀
        }
    }

    static void check() {
        int n = 0;
        long now = System.currentTimeMillis();
        for (Order o : orders) {
            double np = better(o);
            if (Double.isNaN(np)) continue;
            n++;
            String key = o.buy() + o.name() + np;
            Long last = notified.get(key);
            if (last != null && now - last < 300_000) continue;  // 같은 알림은 5분에 한 번
            notified.put(key, now);
            notifyOutbid(o, np);
        }
        outdatedCount = n;
    }

    static MutableComponent button(String text, ChatFormatting color, ClickEvent ev) {
        return Component.literal(text).withStyle(color).withStyle(s -> s.withClickEvent(ev));
    }

    static void notifyOutbid(Order o, double np) {
        double[] q = quotes.get(o.name().toLowerCase(Locale.ROOT));
        double now = o.buy() ? q[1] : q[0];
        MutableComponent msg = Component.literal("[바자 갱신] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal((o.buy() ? "구매 주문 " : "판매 주문 ") + o.name() + " · 내 " + fmt(o.price())
                        + " → 현재 " + (o.buy() ? "최고 구매 " : "최저 판매 ") + fmt(now) + "  ").withStyle(ChatFormatting.YELLOW))
                .append(button("[바자 열기]", ChatFormatting.GREEN, new ClickEvent.RunCommand("/bz " + o.name())))
                .append(Component.literal(" "))
                .append(button("[새 가격 " + fmt(np) + " 복사]", ChatFormatting.AQUA,
                        new ClickEvent.CopyToClipboard(String.format(Locale.ROOT, "%.1f", np))));
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendSystemMessage(msg);
        });
    }
}
