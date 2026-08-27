package com.kgr.key2toolbox.inputfix;

import android.view.KeyEvent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Physical-key -> calculator-input mapping, ported from q25toolbox's
 * Q25KeyTranslator. The digit mapping (Q/W/E/R/S/D/F/Z/X/C -> 0-9, plus raw
 * digit/numpad keys) matches the one already established for the lockscreen
 * PIN keyboard in Key2AccessibilityService.keyCodeToDigit - including
 * Q -> 0, which q25toolbox's own table doesn't have - so this device doesn't
 * end up with two slightly different digit tables. Only the operator/paren/
 * scientific-toggle mappings are new.
 */
public final class Key2KeyTranslator {
    public enum Input {
        DIGIT_0,
        DIGIT_1,
        DIGIT_2,
        DIGIT_3,
        DIGIT_4,
        DIGIT_5,
        DIGIT_6,
        DIGIT_7,
        DIGIT_8,
        DIGIT_9,
        DELETE,
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE,
        DECIMAL,
        PERCENT,
        FACTORIAL,
        LEFT_PAREN,
        RIGHT_PAREN,
        SCIENTIFIC_TOGGLE,
    }

    private Key2KeyTranslator() {
    }

    public static Input toCalculatorInput(int keyCode) {
        Input digit = toDigit(keyCode);
        if (digit != null) return digit;

        switch (keyCode) {
            case KeyEvent.KEYCODE_I:
                return Input.SUBTRACT;
            case KeyEvent.KEYCODE_O:
                return Input.ADD;
            case KeyEvent.KEYCODE_A:
                return Input.MULTIPLY;
            case KeyEvent.KEYCODE_B:
                return Input.FACTORIAL;
            case KeyEvent.KEYCODE_G:
                return Input.DIVIDE;
            case KeyEvent.KEYCODE_M:
                return Input.DECIMAL;
            case KeyEvent.KEYCODE_T:
                return Input.LEFT_PAREN;
            case KeyEvent.KEYCODE_Y:
                return Input.RIGHT_PAREN;
            case KeyEvent.KEYCODE_U:
                return Input.PERCENT;
            case KeyEvent.KEYCODE_SYM:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return Input.SCIENTIFIC_TOGGLE;
            case KeyEvent.KEYCODE_DEL:
                return Input.DELETE;
            default:
                return null;
        }
    }

    /** Same digit mapping as Key2AccessibilityService.keyCodeToDigit, as an Input instead of a String. */
    private static Input toDigit(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return digitInput(keyCode - KeyEvent.KEYCODE_0);
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return digitInput(keyCode - KeyEvent.KEYCODE_NUMPAD_0);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_Q:
                return Input.DIGIT_0;
            case KeyEvent.KEYCODE_W:
                return Input.DIGIT_1;
            case KeyEvent.KEYCODE_E:
                return Input.DIGIT_2;
            case KeyEvent.KEYCODE_R:
                return Input.DIGIT_3;
            case KeyEvent.KEYCODE_S:
                return Input.DIGIT_4;
            case KeyEvent.KEYCODE_D:
                return Input.DIGIT_5;
            case KeyEvent.KEYCODE_F:
                return Input.DIGIT_6;
            case KeyEvent.KEYCODE_Z:
                return Input.DIGIT_7;
            case KeyEvent.KEYCODE_X:
                return Input.DIGIT_8;
            case KeyEvent.KEYCODE_C:
                return Input.DIGIT_9;
            default:
                return null;
        }
    }

    private static Input digitInput(int digit) {
        switch (digit) {
            case 0: return Input.DIGIT_0;
            case 1: return Input.DIGIT_1;
            case 2: return Input.DIGIT_2;
            case 3: return Input.DIGIT_3;
            case 4: return Input.DIGIT_4;
            case 5: return Input.DIGIT_5;
            case 6: return Input.DIGIT_6;
            case 7: return Input.DIGIT_7;
            case 8: return Input.DIGIT_8;
            case 9: return Input.DIGIT_9;
            default: return null;
        }
    }

    public static String calculatorButtonId(Input input) {
        return calculatorButtonId("com.android.calculator2", input);
    }

    public static String calculatorDirectText(Input input) {
        if (input == null) return null;

        switch (input) {
            case FACTORIAL:
                return "!";
            case LEFT_PAREN:
                return "(";
            case RIGHT_PAREN:
                return ")";
            default:
                return null;
        }
    }

    public static String calculatorButtonId(String packageName, Input input) {
        if (packageName == null) return null;
        if (input == null) return null;

        String prefix = packageName + ":id/";
        switch (input) {
            case DIGIT_0:
                return prefix + "digit_0";
            case DIGIT_1:
                return prefix + "digit_1";
            case DIGIT_2:
                return prefix + "digit_2";
            case DIGIT_3:
                return prefix + "digit_3";
            case DIGIT_4:
                return prefix + "digit_4";
            case DIGIT_5:
                return prefix + "digit_5";
            case DIGIT_6:
                return prefix + "digit_6";
            case DIGIT_7:
                return prefix + "digit_7";
            case DIGIT_8:
                return prefix + "digit_8";
            case DIGIT_9:
                return prefix + "digit_9";
            case ADD:
                return prefix + "op_add";
            case SUBTRACT:
                return prefix + "op_sub";
            case MULTIPLY:
                return prefix + "op_mul";
            case DIVIDE:
                return prefix + "op_div";
            case DECIMAL:
                return prefix + "dec_point";
            case PERCENT:
                return prefix + "op_pct";
            case LEFT_PAREN:
            case RIGHT_PAREN:
                return prefix + "parens";
            case DELETE:
                return prefix + "del";
            case SCIENTIFIC_TOGGLE:
                return prefix + "collapse_expand";
            default:
                return null;
        }
    }

    public static List<String> calculatorButtonFallbackLabels(Input input) {
        if (input == null) return Collections.emptyList();

        switch (input) {
            case DIGIT_0:
                return Collections.singletonList("0");
            case DIGIT_1:
                return Collections.singletonList("1");
            case DIGIT_2:
                return Collections.singletonList("2");
            case DIGIT_3:
                return Collections.singletonList("3");
            case DIGIT_4:
                return Collections.singletonList("4");
            case DIGIT_5:
                return Collections.singletonList("5");
            case DIGIT_6:
                return Collections.singletonList("6");
            case DIGIT_7:
                return Collections.singletonList("7");
            case DIGIT_8:
                return Collections.singletonList("8");
            case DIGIT_9:
                return Collections.singletonList("9");
            case ADD:
                return Collections.singletonList("plus");
            case SUBTRACT:
                return Collections.singletonList("minus");
            case MULTIPLY:
                return Collections.singletonList("multiply");
            case DIVIDE:
                return Collections.singletonList("divide");
            case DECIMAL:
                return Collections.singletonList("point");
            case PERCENT:
                return Collections.singletonList("percent");
            case LEFT_PAREN:
            case RIGHT_PAREN:
                return Collections.singletonList("left or right parenthesis");
            case DELETE:
                return Collections.singletonList("Delete");
            case SCIENTIFIC_TOGGLE:
                return Arrays.asList("Show scientific buttons", "Hide scientific buttons");
            default:
                return Collections.emptyList();
        }
    }
}
