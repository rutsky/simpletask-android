/**
 *
 * Copyright (c) 2015 Vojtech Kral
 *
 * LICENSE:
 *
 * Simpletas is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 2 of the License, or (at your option) any
 * later version.
 *
 * Simpletask is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with Sinpletask.  If not, see
 * <http://www.gnu.org/licenses/>.
 *
 * @author Vojtech Kral
 * @license http://www.gnu.org/licenses/gpl.html
 * @copyright 2015 Vojtech Kral
 */

package nl.mpcjanssen.simpletask;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Reminders;
import hirondelle.date4j.DateTime;
import nl.mpcjanssen.simpletask.task.Task;
import nl.mpcjanssen.simpletask.task.TodoList;
import nl.mpcjanssen.simpletask.util.Util;


import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


public class CalendarSync {
    private static final String PACKAGE = TodoApplication.getAppContext().getPackageName();
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static final String ACCOUNT_NAME = "Simpletask Calendar";
    private static final String ACCOUNT_TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL;
    private static final Uri CAL_URI = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE).build();
    private static final String CAL_NAME = "simpletask_reminders_v34SsjC7mwK9WSVI";
    private static final int CAL_COLOR = Color.BLUE;       // Chosen arbitrarily...
    private static final int EVT_DURATION_DAY = 24*60*60*1000;  // ie. 24 hours

    private static final int SYNC_DELAY_MS = 1000;
    private static final String TAG = CalendarSync.class.getSimpleName();
    private final Logger log;

    private class SyncRunnable implements Runnable {
        @Override
        public void run() {
            try {
                CalendarSync.this.sync();
            } catch (Exception e) {
                log.error(TAG, "STPE exception", e);
            }
        }
    }

    private TodoApplication m_app;
    private SyncRunnable m_sync_runnable;
    private ContentResolver m_cr;
    private int m_sync_type;
    private int m_rem_margin = 1440;
    private DateTime m_rem_time = DateTime.forTimeOnly(12, 0, 0, 0);
    private ScheduledThreadPoolExecutor m_stpe;

    private void calendarError(Exception e) {
        if (e != null) {
            log.error(TAG, "Error writing calendar", e);
        } else {
            log.error(TAG, "Error writing calendar");
        }
        m_sync_type = 0;
        Util.showToastShort(TodoApplication.getAppContext(), R.string.calendar_write_error);
    }

    private long getCalID() {
        final String[] projection = {Calendars._ID, Calendars.NAME};
        final String selection = Calendars.NAME+" = ?";
        final String[] args = {CAL_NAME};
        Cursor cursor = m_cr.query(CAL_URI, projection, selection, args, null);
        if (cursor == null) throw new IllegalArgumentException("null cursor");
        if (cursor.getCount() == 0) return -1;
        cursor.moveToFirst();
        long ret = cursor.getLong(0);
        cursor.close();
        return ret;
    }

    private void addCalendar() {
        final ContentValues cv = new ContentValues();
        cv.put(Calendars.ACCOUNT_NAME, ACCOUNT_NAME);
        cv.put(Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE);
        cv.put(Calendars.NAME, CAL_NAME);
        cv.put(Calendars.CALENDAR_DISPLAY_NAME, m_app.getString(R.string.calendar_disp_name));
        cv.put(Calendars.CALENDAR_COLOR, CAL_COLOR);
        cv.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ);
        cv.put(Calendars.OWNER_ACCOUNT, ACCOUNT_NAME);
        cv.put(Calendars.VISIBLE, 1);
        cv.put(Calendars.SYNC_EVENTS, 1);
        m_cr.insert(CAL_URI, cv);
    }

    private void removeCalendar() {
        final String selection = Calendars.NAME+" = ?";
        final String[] args = {CAL_NAME};
        try {
            int ret = m_cr.delete(CAL_URI, selection, args);
            if (ret == 1) log.debug(TAG, "Calendar removed");
            else log.error(TAG, "Unexpected return value while removing calendar: " + ret);
        }
        catch (Exception e) {
            log.error(TAG, "Exception while removing calendar", e);
        }
    }

    @SuppressWarnings("NewApi")
    private void insertEvt(long calID, DateTime date, String title, String description) {
        ContentValues values = new ContentValues();

        TimeZone localZone = Calendar.getInstance().getTimeZone();
        long dtstart = date.getMilliseconds(UTC);

        // Event:
        values.put(Events.CALENDAR_ID, calID);
        values.put(Events.TITLE, title);
        values.put(Events.DTSTART, dtstart);
        values.put(Events.DTEND, dtstart + EVT_DURATION_DAY);  // Needs to be set to DTSTART +24h, otherwise reminders don't work
        values.put(Events.ALL_DAY, 1);
        values.put(Events.DESCRIPTION, description);
        values.put(Events.EVENT_TIMEZONE, UTC.getID());
        values.put(Events.STATUS, Events.STATUS_CONFIRMED);
        values.put(Events.HAS_ATTENDEE_DATA, true);      // If this is not set, Calendar app is confused about Event.STATUS
        values.put(Events.CUSTOM_APP_PACKAGE, PACKAGE);
        values.put(Events.CUSTOM_APP_URI, Uri.withAppendedPath(Simpletask.URI_SEARCH, title).toString());
        Uri uri = m_cr.insert(Events.CONTENT_URI, values);

        // Reminder:
        // Only create reminder if it's in the future, otherwise it would go off immediately
        // NOTE: DateTime.minus()/plus() only accept values >=0 and <10000 (goddamnit date4j!), hence the division.
        DateTime remDate = date.minus(0, 0, 0,  m_rem_margin / 60,  m_rem_margin % 60, 0, 0, DateTime.DayOverflow.Spillover);
        remDate = remDate.plus(0, 0, 0, m_rem_time.getHour(), m_rem_time.getMinute(), 0, 0, DateTime.DayOverflow.Spillover);
        if (remDate.isInTheFuture(localZone)) {
            long evtID = Long.parseLong(uri.getLastPathSegment());
            values.clear();
            values.put(Reminders.EVENT_ID, evtID);
            values.put(Reminders.MINUTES, remDate.numSecondsFrom(date) / 60);
            values.put(Reminders.METHOD, Reminders.METHOD_ALERT);
            m_cr.insert(Reminders.CONTENT_URI, values);
        }
    }

    private void insertEvts(long calID, final List<Task> tasks) {
        for (Task task: tasks) {
            if (task.isCompleted()) continue;

            DateTime dt;
            String text = null;

            // Check due date:
            if ((m_sync_type & SYNC_TYPE_DUES) != 0) {
                dt = task.getDueDate();
                if (dt != null) {
                    text = task.getText();
                    insertEvt(calID, dt, text, m_app.getString(R.string.calendar_sync_desc_due));
                }
            }

            // Check threshold date:
            if ((m_sync_type & SYNC_TYPE_THRESHOLDS) != 0) {
                dt = task.getThresholdDate();
                if (dt != null) {
                    if (text == null) text = task.getText();
                    insertEvt(calID, dt, text, m_app.getString(R.string.calendar_sync_desc_thre));
                }
            }
        }
    }

    private void purgeEvts(long calID) {
        final String selection = Events.CALENDAR_ID+" = ?";
        final String args[] = {""};
        args[0] = String.valueOf(calID);
        m_cr.delete(Events.CONTENT_URI, selection, args);
    }

    private void sync() {
        long calID;

        try {
            calID = getCalID();
            if (m_sync_type == 0) {
                if (calID >= 0) removeCalendar();
                return;
            }

            if (calID < 0) {
                addCalendar();
                calID = getCalID();  // This needs to be checked, various privacy guards might cause the calendar to silently not be added
                if (calID < 0) {
                    calendarError(null);
                    return;
                }
            }

            TodoList tl = m_app.getTodoList();

            final List<Task> tasks = tl.getTasks();
            setReminderDays(m_app.getReminderDays());
            setReminderTime(m_app.getReminderTime());

            log.debug(TAG, "Syncing due/threshold calendar reminders...");
            purgeEvts(calID);
            insertEvts(calID, tasks);
        } catch (Exception e) {
            calendarError(e);
        }
    }

    private void setSyncType(int syncType) {
        if (syncType == m_sync_type) return;
        if (!TodoApplication.ATLEAST_API16) {
            m_sync_type = 0;
            return;
        }
        m_sync_type = syncType;
        syncLater();
    }



    public static final int SYNC_TYPE_DUES = 1, SYNC_TYPE_THRESHOLDS = 2;

    public CalendarSync(TodoApplication app, boolean syncDues, boolean syncThresholds) {
        log = Logger.INSTANCE;
        m_app = app;
        m_sync_runnable = new SyncRunnable();
        m_cr = app.getContentResolver();
        m_stpe = new ScheduledThreadPoolExecutor(1);
        int syncType = 0;
        if (syncDues) syncType |= SYNC_TYPE_DUES;
        if (syncThresholds) syncType |= SYNC_TYPE_THRESHOLDS;
        setSyncType(syncType);
    }

    public void syncLater() {
        m_stpe.getQueue().clear();
        m_stpe.schedule(m_sync_runnable, SYNC_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    public void setSyncDues(boolean bool) {
        int syncType = bool ? m_sync_type | SYNC_TYPE_DUES : m_sync_type & ~SYNC_TYPE_DUES;
        setSyncType(syncType);
    }

    public void setSyncThresholds(boolean bool) {
        int syncType = bool ? m_sync_type | SYNC_TYPE_THRESHOLDS : m_sync_type & ~SYNC_TYPE_THRESHOLDS;
        setSyncType(syncType);
    }

    public void setReminderDays(int days) {
        m_rem_margin = days * 1440;
    }

    public void setReminderTime(int time) {
        m_rem_time = DateTime.forTimeOnly(time / 60, time % 60, 0, 0);
    }
}
