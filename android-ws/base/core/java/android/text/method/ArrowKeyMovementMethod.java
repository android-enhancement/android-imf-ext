/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.text.method;

import android.util.Log;
import android.view.KeyEvent;
import android.graphics.Rect;
import android.text.*;
import android.widget.TextView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.MotionEvent;

// XXX this doesn't extend MetaKeyKeyListener because the signatures
// don't match.  Need to figure that out.  Meanwhile the meta keys
// won't work in fields that don't take input.

public class
ArrowKeyMovementMethod
implements MovementMethod
{
    private boolean up(TextView widget, Spannable buffer) {
        boolean cap = (MetaKeyKeyListener.getMetaState(buffer,
                        KeyEvent.META_SHIFT_ON) == 1) ||
                      (MetaKeyKeyListener.getMetaState(buffer,
                        MetaKeyKeyListener.META_SELECTING) != 0);
        boolean alt = MetaKeyKeyListener.getMetaState(buffer,
                        KeyEvent.META_ALT_ON) == 1;
        Layout layout = widget.getLayout();

        if (cap) {
            if (alt) {
                Selection.extendSelection(buffer, 0);
                return true;
            } else {
                return Selection.extendUp(buffer, layout);
            }
        } else {
            if (alt) {
                Selection.setSelection(buffer, 0);
                return true;
            } else {
                return Selection.moveUp(buffer, layout); 
            }
        }
    }

    private boolean down(TextView widget, Spannable buffer) {
        boolean cap = (MetaKeyKeyListener.getMetaState(buffer,
                        KeyEvent.META_SHIFT_ON) == 1) ||
                      (MetaKeyKeyListener.getMetaState(buffer,
                        MetaKeyKeyListener.META_SELECTING) != 0);
        boolean alt = MetaKeyKeyListener.getMetaState(buffer,
                        KeyEvent.META_ALT_ON) == 1;
        Layout layout = widget.getLayout();

        if (cap) {
            if (alt) {
                Selection.extendSelection(buffer, buffer.length());
                return true;
            } else {
                return Selection.extendDown(buffer, layout);
            }
        } else {
            if (alt) {
                Selection.setSelection(buffer, buffer.length());
                return true;
            } else {
                return Selection.moveDown(buffer, layout); 
            }
        }
    }

    private boolean left(TextView widget, Spannable buffer) {
        boolean cap = (MetaKeyKeyListener.getMetaState(buffer,
                        KeyEvent.META_SHIFT_ON) == 1) ||
                      (MetaKeyKeyListener.getMetaState(buffer,
                        MetaKeyKeyListener.META_SELECTING) != 0);
        boolean alt = MetaKeyKeyListener.getMetaState(buffer,
                        KeyEvent.META_ALT_ON) == 1;
        Layout layout = widget.getLayout();

        if (cap) {
            if (alt) {
                return Selection.extendToLeftEdge(buffer, layout);
            } else {
                return Selection.extendLeft(buffer, layout);
            }
        } else {
            if (alt) {
                return Selection.moveToLeftEdge(buffer, layout);
            } else {
                return Selection.moveLeft(buffer, layout); 
            }
        }
    }

    private boolean right(TextView widget, Spannable buffer) {
        boolean cap = (MetaKeyKeyListener.getMetaState(buffer,
                        KeyEvent.META_SHIFT_ON) == 1) ||
                      (MetaKeyKeyListener.getMetaState(buffer,
                        MetaKeyKeyListener.META_SELECTING) != 0);
        boolean alt = MetaKeyKeyListener.getMetaState(buffer,
                        KeyEvent.META_ALT_ON) == 1;
        Layout layout = widget.getLayout();

        if (cap) {
            if (alt) {
                return Selection.extendToRightEdge(buffer, layout);
            } else {
                return Selection.extendRight(buffer, layout);
            }
        } else {
            if (alt) {
                return Selection.moveToRightEdge(buffer, layout);
            } else {
                return Selection.moveRight(buffer, layout); 
            }
        }
    }

    public boolean onKeyDown(TextView widget, Spannable buffer, int keyCode, KeyEvent event) {
        if (executeDown(widget, buffer, keyCode)) {
            MetaKeyKeyListener.adjustMetaAfterKeypress(buffer);
            MetaKeyKeyListener.resetLockedMeta(buffer);
            return true;
        }

        return false;
    }

    private boolean executeDown(TextView widget, Spannable buffer, int keyCode) {
        boolean handled = false;

        switch (keyCode) {
        case KeyEvent.KEYCODE_DPAD_UP:
            handled |= up(widget, buffer);
            break;

        case KeyEvent.KEYCODE_DPAD_DOWN:
            handled |= down(widget, buffer);
            break;

        case KeyEvent.KEYCODE_DPAD_LEFT:
            handled |= left(widget, buffer);
            break;

        case KeyEvent.KEYCODE_DPAD_RIGHT:
            handled |= right(widget, buffer);
            break;

        case KeyEvent.KEYCODE_DPAD_CENTER:
            if (MetaKeyKeyListener.getMetaState(buffer, MetaKeyKeyListener.META_SELECTING) != 0) {
                if (widget.showContextMenu()) {
                    handled = true;
                }
            }
        }

        if (handled) {
            MetaKeyKeyListener.adjustMetaAfterKeypress(buffer);
            MetaKeyKeyListener.resetLockedMeta(buffer);
        }

        return handled;
    }

    public boolean onKeyUp(TextView widget, Spannable buffer, int keyCode, KeyEvent event) {
        return false;
    }

    public boolean onKeyOther(TextView view, Spannable text, KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_UNKNOWN
                && event.getAction() == KeyEvent.ACTION_MULTIPLE) {
            int repeat = event.getRepeatCount();
            boolean handled = false;
            while ((--repeat) > 0) {
                handled |= executeDown(view, text, code);
            }
            return handled;
        }
        return false;
    }
    
    public boolean onTrackballEvent(TextView widget, Spannable text,
            MotionEvent event) {
        return false;
    }
    
    public boolean onTouchEvent(TextView widget, Spannable buffer,
                                MotionEvent event) {
        int initialScrollX = -1, initialScrollY = -1;
        if (event.getAction() == MotionEvent.ACTION_UP) {
            initialScrollX = Touch.getInitialScrollX(widget, buffer);
            initialScrollY = Touch.getInitialScrollY(widget, buffer);
        }
        
        boolean handled = Touch.onTouchEvent(widget, buffer, event);

        if (widget.isFocused() && !widget.didTouchFocusSelect()) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                // If we have scrolled, then the up shouldn't move the cursor,
                // but we do need to make sure the cursor is still visible at
                // the current scroll offset to avoid the scroll jumping later
                // to show it.
                if ((initialScrollY >= 0 && initialScrollY != widget.getScrollY()) ||
                        (initialScrollX >= 0 && initialScrollX != widget.getScrollX())) {
                    widget.moveCursorToVisibleOffset();
                    return true;
                }
                
                int x = (int) event.getX();
                int y = (int) event.getY();

                x -= widget.getTotalPaddingLeft();
                y -= widget.getTotalPaddingTop();

                // Clamp the position to inside of the view.
                if (x < 0) {
                    x = 0;
                } else if (x >= (widget.getWidth()-widget.getTotalPaddingRight())) {
                    x = widget.getWidth()-widget.getTotalPaddingRight() - 1;
                }
                if (y < 0) {
                    y = 0;
                } else if (y >= (widget.getHeight()-widget.getTotalPaddingBottom())) {
                    y = widget.getHeight()-widget.getTotalPaddingBottom() - 1;
                }
                
                x += widget.getScrollX();
                y += widget.getScrollY();

                Layout layout = widget.getLayout();
                int line = layout.getLineForVertical(y);
                
                int off = layout.getOffsetForHorizontal(line, x);

                // XXX should do the same adjust for x as we do for the line.
                
                boolean cap = (MetaKeyKeyListener.getMetaState(buffer,
                                KeyEvent.META_SHIFT_ON) == 1) ||
                              (MetaKeyKeyListener.getMetaState(buffer,
                                MetaKeyKeyListener.META_SELECTING) != 0);

                DoubleTapState[] tap = buffer.getSpans(0, buffer.length(),
                                                       DoubleTapState.class);
                boolean doubletap = false;

                if (tap.length > 0) {
                    if (event.getEventTime() - tap[0].mWhen <=
                        ViewConfiguration.getDoubleTapTimeout()) {
                        if (sameWord(buffer, off, Selection.getSelectionEnd(buffer))) {
                            doubletap = true;
                        }
                    }

                    tap[0].mWhen = event.getEventTime();
                } else {
                    DoubleTapState newtap = new DoubleTapState();
                    newtap.mWhen = event.getEventTime();
                    buffer.setSpan(newtap, 0, buffer.length(),
                                   Spannable.SPAN_INCLUSIVE_INCLUSIVE);
                }

                if (cap) {
                    Selection.extendSelection(buffer, off);
                } else if (doubletap) {
                    Selection.setSelection(buffer,
                                           findWordStart(buffer, off),
                                           findWordEnd(buffer, off));
                } else {
                    Selection.setSelection(buffer, off);
                }

                MetaKeyKeyListener.adjustMetaAfterKeypress(buffer);
                MetaKeyKeyListener.resetLockedMeta(buffer);

                return true;
            }
        }

        return handled;
    }

    private static class DoubleTapState implements NoCopySpan {
        long mWhen;
    }

    private static boolean sameWord(CharSequence text, int one, int two) {
        int start = findWordStart(text, one);
        int end = findWordEnd(text, one);

        if (end == start) {
            return false;
        }

        return start == findWordStart(text, two) &&
               end == findWordEnd(text, two);
    }

    // TODO: Unify with TextView.getWordForDictionary()
    private static int findWordStart(CharSequence text, int start) {
        for (; start > 0; start--) {
            char c = text.charAt(start - 1);
            int type = Character.getType(c);

            if (c != '\'' &&
                type != Character.UPPERCASE_LETTER &&
                type != Character.LOWERCASE_LETTER &&
                type != Character.TITLECASE_LETTER &&
                type != Character.MODIFIER_LETTER &&
                type != Character.DECIMAL_DIGIT_NUMBER) {
                break;
            }
        }

        return start;
    }

    // TODO: Unify with TextView.getWordForDictionary()
    private static int findWordEnd(CharSequence text, int end) {
        int len = text.length();

        for (; end < len; end++) {
            char c = text.charAt(end);
            int type = Character.getType(c);

            if (c != '\'' &&
                type != Character.UPPERCASE_LETTER &&
                type != Character.LOWERCASE_LETTER &&
                type != Character.TITLECASE_LETTER &&
                type != Character.MODIFIER_LETTER &&
                type != Character.DECIMAL_DIGIT_NUMBER) {
                break;
            }
        }

        return end;
    }

    public boolean canSelectArbitrarily() {
        return true;
    }

    public void initialize(TextView widget, Spannable text) {
        Selection.setSelection(text, 0);
    }

    public void onTakeFocus(TextView view, Spannable text, int dir) {
        if ((dir & (View.FOCUS_FORWARD | View.FOCUS_DOWN)) != 0) {
            Layout layout = view.getLayout();

            if (layout == null) {
                /*
                 * This shouldn't be null, but do something sensible if it is.
                 */
                Selection.setSelection(text, text.length());
            } else {
                /*
                 * Put the cursor at the end of the first line, which is
                 * either the last offset if there is only one line, or the
                 * offset before the first character of the second line
                 * if there is more than one line.
                 */
                if (layout.getLineCount() == 1) {
                    Selection.setSelection(text, text.length());
                } else {
                    Selection.setSelection(text, layout.getLineStart(1) - 1);
                }
            }
        } else {
            Selection.setSelection(text, text.length());
        }
    }

    public static MovementMethod getInstance() {
        if (sInstance == null)
            sInstance = new ArrowKeyMovementMethod();

        return sInstance;
    }

    private static ArrowKeyMovementMethod sInstance;
}
