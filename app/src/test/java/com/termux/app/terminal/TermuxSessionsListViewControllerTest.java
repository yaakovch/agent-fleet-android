package com.termux.app.terminal;

import com.termux.app.TermuxActivity;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;

@RunWith(RobolectricTestRunner.class)
public class TermuxSessionsListViewControllerTest {

    @Test
    public void rebuildingVisibleSessionsDoesNotReenterDataSetNotification() {
        TermuxActivity activity = Robolectric.buildActivity(TermuxActivity.class).get();
        TermuxSessionsListViewController controller =
            new TermuxSessionsListViewController(activity, new ArrayList<>());

        for (int i = 0; i < 10; i++) controller.notifyDataSetChanged();

        Assert.assertEquals(0, controller.getCount());
    }
}
