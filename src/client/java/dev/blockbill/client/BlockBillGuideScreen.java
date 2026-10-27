package dev.blockbill.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Read-only guide; subscriptions and purchases remain server-owned commands. */
public final class BlockBillGuideScreen extends Screen {
    private static final List<String> SECTIONS = List.of(
            "start", "services", "workshop", "trade", "fishing", "storage", "bundles", "plans",
            "exchange", "commands", "autorenew", "time", "safety");
    private static final int TEXT_TOP = 64;
    private static final int LINE_HEIGHT = 12;

    private final Screen parent;
    private final List<Page> pages = new ArrayList<>();
    private int pageIndex;
    private int contentWidth;
    private Button previous;
    private Button next;

    public BlockBillGuideScreen(Screen parent) {
        super(Component.translatable("guide.blockbill.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Page previousPage = pages.isEmpty() ? null : pages.get(pageIndex);
        contentWidth = Math.max(80, Math.min(440, width - 32));
        int linesPerPage = Math.max(1, (height - TEXT_TOP - 56) / LINE_HEIGHT);
        pages.clear();
        for (String section : SECTIONS) {
            List<FormattedCharSequence> lines = font.split(Component.translatable("guide.blockbill." + section + ".body"),
                    contentWidth);
            for (int start = 0; start < lines.size(); start += linesPerPage) {
                pages.add(new Page(section, start / linesPerPage,
                        List.copyOf(lines.subList(start, Math.min(start + linesPerPage, lines.size())))));
            }
        }
        // Resizing changes the number of pages before the current section. Keep the reader
        // in that section instead of interpreting the old global index in the new layout.
        pageIndex = 0;
        if (previousPage != null) {
            for (int i = 0; i < pages.size(); i++) {
                Page page = pages.get(i);
                if (page.section().equals(previousPage.section()) && page.sectionPage() <= previousPage.sectionPage()) {
                    pageIndex = i;
                }
            }
        }
        int buttonWidth = Math.min(120, (width - 32) / 3);
        int left = (width - 3 * buttonWidth - 12) / 2;
        int bottom = height - 28;
        previous = addRenderableWidget(Button.builder(Component.translatable("guide.blockbill.previous"), button -> turn(-1))
                .bounds(left, bottom, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("guide.blockbill.close"), button -> onClose())
                .bounds(left + buttonWidth + 6, bottom, buttonWidth, 20).build());
        next = addRenderableWidget(Button.builder(Component.translatable("guide.blockbill.next"), button -> turn(1))
                .bounds(left + 2 * buttonWidth + 12, bottom, buttonWidth, 20).build());
        updateNavigation();
    }

    private void turn(int direction) {
        pageIndex = Math.max(0, Math.min(pages.size() - 1, pageIndex + direction));
        updateNavigation();
        triggerImmediateNarration(true);
    }

    private void updateNavigation() {
        previous.active = pageIndex > 0;
        next.active = pageIndex < pages.size() - 1;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xEE101820);
        graphics.centeredText(font, title, width / 2, 14, 0xFFFFD166);
        if (!pages.isEmpty()) {
            Page page = pages.get(pageIndex);
            graphics.centeredText(font, Component.translatable("guide.blockbill." + page.section() + ".title"),
                    width / 2, 36, 0xFFFFFFFF);
            int y = TEXT_TOP;
            for (FormattedCharSequence line : page.lines()) {
                graphics.text(font, line, (width - contentWidth) / 2, y, 0xFFE4ECF2);
                y += LINE_HEIGHT;
            }
            graphics.centeredText(font, Component.translatable("guide.blockbill.page", pageIndex + 1, pages.size()),
                    width / 2, height - 44, 0xFFABC1CE);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public Component getNarrationMessage() {
        if (pages.isEmpty()) {
            return title;
        }
        Page page = pages.get(pageIndex);
        return title.copy().append(". ")
                .append(Component.translatable("guide.blockbill." + page.section() + ".title"))
                .append(". ").append(Component.translatable("guide.blockbill." + page.section() + ".body"));
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(minecraft.player == null ? null : parent);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }

    private record Page(String section, int sectionPage, List<FormattedCharSequence> lines) {
    }
}
