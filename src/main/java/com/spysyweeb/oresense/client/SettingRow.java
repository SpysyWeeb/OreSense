package com.spysyweeb.oresense.client;

import com.spysyweeb.oresense.Config;
import com.spysyweeb.oresense.LiveConfig;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * One setting on the settings screen: its name and description (lang oresense.settings.&lt;key&gt;
 * and .desc, where key is "section.name" from the value's place in the config file, or a
 * description per value, {@link #describedBy}), and its control. It reads and changes the value
 * only through its file's {@link LiveConfig} (Config.LIVE or ClientConfig.LIVE): every control
 * change is set in memory at once; the screen saves on Done and calls {@link #restore()} on Cancel.
 * A row may need another setting to be on ({@link #enabledBy}); the list then draws it inactive
 * while that setting's pending value is off.
 */
public abstract class SettingRow<T> {
    public static final int CONTROL_WIDTH = 120;
    public static final int CONTROL_HEIGHT = 20;

    private final LiveConfig live;
    private final Config.Value<T> value;
    private final String key;
    private final boolean gameplay;
    private T captured;
    private @Nullable BooleanSupplier enabledBy;
    private Component needs = CommonComponents.EMPTY;
    private @Nullable Function<T, Component> describedBy;

    protected SettingRow(LiveConfig live, Config.Value<T> value, boolean gameplay) {
        this.live = live;
        this.value = value;
        this.key = value.section() + "." + value.key();
        this.gameplay = gameplay;
    }

    public Component name() {
        return Component.translatable("oresense.settings." + key);
    }

    /**
     * The text under the name: with {@link #describedBy}, the description of the current (pending)
     * value, otherwise lang oresense.settings.&lt;key&gt;.desc. The list asks every frame.
     */
    public Component description() {
        return describedBy != null ? describedBy.apply(get())
                : Component.translatable("oresense.settings." + key + ".desc");
    }

    /**
     * Gives each value its own description (an enum row whose values work differently), so the
     * text under the name follows the control: cycling the button swaps it at once.
     */
    public SettingRow<T> describedBy(Function<T, Component> describedBy) {
        this.describedBy = describedBy;
        return this;
    }

    /** A server (world) setting rather than a display setting of this computer. */
    public boolean gameplay() {
        return gameplay;
    }

    /**
     * Makes this row need another setting: while {@code enabledBy} answers false the list draws the
     * row inactive (its control disabled, its name grey, the tooltip {@link #needsText()}). The list
     * asks every frame for every row, drawn or not, and again before it handles a key, a click or a
     * focus move, so the row follows the other row's pending value at once and keyboard focus never
     * lands on it while it is inactive. This row's own value is never changed by it. {@code needs} is
     * the other setting's name.
     */
    public SettingRow<T> enabledBy(BooleanSupplier enabledBy, Component needs) {
        this.enabledBy = enabledBy;
        this.needs = needs;
        return this;
    }

    /** False while the setting this row needs ({@link #enabledBy}) is off; always true for a row that needs none. */
    public boolean enabled() {
        return enabledBy == null || enabledBy.getAsBoolean();
    }

    /** Why the row is inactive: "Needs &lt;the setting it needs&gt;." */
    public Component needsText() {
        return Component.translatable("oresense.settings.needs", needs);
    }

    public T get() {
        return live.get(value);
    }

    public void set(T newValue) {
        if (!Objects.equals(get(), newValue)) live.set(value, newValue);
    }

    /** Remembers the value as the screen opened, for Cancel. */
    public void capture() {
        captured = get();
    }

    /** True when the value is not what it was when the screen opened. */
    public boolean changed() {
        return !Objects.equals(get(), captured);
    }

    /** Puts back the value from when the screen opened. */
    public void restore() {
        set(captured);
    }

    /** Sets the default (pending Done like any change) and shows it. */
    public void reset() {
        set(value.defaultValue());
        refresh();
    }

    /** A new control (CONTROL_WIDTH x CONTROL_HEIGHT, positioned by the list) showing the current value. */
    public abstract AbstractWidget createControl();

    /** Makes the control show the current value (after a reset, or a change from elsewhere). */
    public abstract void refresh();

    // ---- kinds ----

    /** An int: a slider labelled "Name: value" over the setting's range. */
    public static SettingRow<Integer> slider(LiveConfig live, Config.Value<Integer> value, boolean gameplay) {
        return new IntRow(live, value, live.min(value), live.max(value), gameplay);
    }

    /** A boolean: a button reading On / Off. */
    public static SettingRow<Boolean> toggle(LiveConfig live, Config.Value<Boolean> value, boolean gameplay) {
        return new ChoiceRow<>(live, value, List.of(true, false), UnaryOperator.identity(),
                on -> Component.translatable(on ? "oresense.settings.on" : "oresense.settings.off"), gameplay);
    }

    /** One of a few values: a button that steps through them, showing {@code label} of each. */
    public static <T> SettingRow<T> choice(LiveConfig live, Config.Value<T> value, List<T> values,
                                           Function<T, Component> label, boolean gameplay) {
        return choice(live, value, values, UnaryOperator.identity(), label, gameplay);
    }

    /**
     * One of a few values, for a setting whose file can hold a value that is none of them (the skin
     * id: a skin from another version, a typo). {@code resolve} maps the stored value to the one of
     * {@code values} the game uses in its place (an unknown skin id to compass); the button shows
     * that value and a click steps on from it.
     */
    public static <T> SettingRow<T> choice(LiveConfig live, Config.Value<T> value, List<T> values,
                                           UnaryOperator<T> resolve, Function<T, Component> label,
                                           boolean gameplay) {
        return new ChoiceRow<>(live, value, values, resolve, label, gameplay);
    }

    private static final class IntRow extends SettingRow<Integer> {
        private final int min, max;
        private Slider slider;

        IntRow(LiveConfig live, Config.Value<Integer> value, int min, int max, boolean gameplay) {
            super(live, value, gameplay);
            this.min = min;
            this.max = max;
        }

        @Override
        public AbstractWidget createControl() {
            slider = new Slider();
            return slider;
        }

        @Override
        public void refresh() {
            if (slider != null) slider.show(get());
        }

        private final class Slider extends AbstractSliderButton {
            Slider() {
                super(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT, Component.empty(), 0.0);
                show(get());
            }

            void show(int v) {
                value = max == min ? 0.0 : (v - min) / (double) (max - min);
                updateMessage();
            }

            private int current() {
                return min + (int) Math.round(value * (max - min));
            }

            @Override
            protected void updateMessage() {
                setMessage(CommonComponents.optionNameValue(name(), Component.literal(Integer.toString(current()))));
            }

            @Override
            protected void applyValue() {
                set(current());
            }
        }
    }

    private static final class ChoiceRow<T> extends SettingRow<T> {
        private final List<T> values;
        private final UnaryOperator<T> resolve;
        private final Function<T, Component> label;
        private Button button;

        ChoiceRow(LiveConfig live, Config.Value<T> value, List<T> values, UnaryOperator<T> resolve,
                  Function<T, Component> label, boolean gameplay) {
            super(live, value, gameplay);
            this.values = List.copyOf(values);
            this.resolve = resolve;
            this.label = label;
        }

        /** The value the button shows and steps on from: the stored one, resolved to one of {@code values}. */
        private T shown() {
            return resolve.apply(get());
        }

        @Override
        public AbstractWidget createControl() {
            button = Button.builder(label.apply(shown()), b -> {
                int at = values.indexOf(shown());
                set(values.get((at + 1) % values.size()));
                refresh();
            }).size(CONTROL_WIDTH, CONTROL_HEIGHT).build();
            return button;
        }

        @Override
        public void refresh() {
            if (button != null) button.setMessage(label.apply(shown()));
        }
    }
}
