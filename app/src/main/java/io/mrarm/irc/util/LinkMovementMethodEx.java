package io.mrarm.irc.util;

import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.widget.TextView;

public class LinkMovementMethodEx extends LinkMovementMethod {

    private static LinkMovementMethodEx sInstance;

    public static LinkMovementMethodEx getInstance() {
        if (sInstance == null)
            sInstance = new LinkMovementMethodEx();
        return sInstance;
    }

    @Override
    public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
        int action = event.getAction();

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_DOWN) {
            int x = (int) event.getX();
            int y = (int) event.getY();

            x -= widget.getTotalPaddingLeft();
            y -= widget.getTotalPaddingTop();

            x += widget.getScrollX();
            y += widget.getScrollY();

            Layout layout = widget.getLayout();
            if (layout != null) {
                int line = layout.getLineForVertical(y);
                int off = layout.getOffsetForHorizontal(line, x);

                ClickableSpan[] link = buffer.getSpans(off, off, ClickableSpan.class);

                if (link.length != 0) {
                    if (action == MotionEvent.ACTION_UP) {
                        link[0].onClick(widget);
                        Selection.removeSelection(buffer);
                        return true;
                    } else if (action == MotionEvent.ACTION_DOWN) {
                        // Do not set selection to avoid highlighting the clicked link
                        return true;
                    }
                } else {
                    Selection.removeSelection(buffer);
                }
            }
        }

        return super.onTouchEvent(widget, buffer, event);
    }
}
