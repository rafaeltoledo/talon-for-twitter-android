package com.klinker.android.talon.ui.fragments;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Fragment;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import com.klinker.android.talon.adapters.CursorListLoader;
import com.klinker.android.talon.adapters.TimeLineCursorAdapter;
import com.klinker.android.talon.services.TimelineRefreshService;
import com.klinker.android.talon.sq_lite.MentionsDataSource;
import com.klinker.android.talon.ui.MainActivity;
import com.klinker.android.talon.utils.App;
import com.klinker.android.talon.R;
import com.klinker.android.talon.settings.AppSettings;
import com.klinker.android.talon.sq_lite.HomeDataSource;
import com.klinker.android.talon.utils.*;
import de.keyboardsurfer.android.widget.crouton.Crouton;
import de.keyboardsurfer.android.widget.crouton.Style;
import org.lucasr.smoothie.AsyncListView;
import org.lucasr.smoothie.ItemManager;
import twitter4j.Paging;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.User;
import uk.co.senab.actionbarpulltorefresh.library.ActionBarPullToRefresh;
import uk.co.senab.actionbarpulltorefresh.library.PullToRefreshAttacher;
import uk.co.senab.actionbarpulltorefresh.library.PullToRefreshLayout;
import uk.co.senab.actionbarpulltorefresh.library.listeners.OnRefreshListener;
import uk.co.senab.bitmapcache.BitmapLruCache;

import java.util.Date;
import java.util.List;

public class HomeFragment extends Fragment implements OnRefreshListener {

    public static final int HOME_REFRESH_ID = 121;

    private static Twitter twitter;
    private ConnectionDetector cd;

    public static AsyncListView listView;
    private CursorAdapter cursorAdapter;

    public AppSettings settings;
    private SharedPreferences sharedPrefs;

    private PullToRefreshAttacher mPullToRefreshAttacher;
    private PullToRefreshLayout mPullToRefreshLayout;

    private HomeDataSource dataSource;

    static Activity context;

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        context = activity;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        settings = new AppSettings(context);
        cd = new ConnectionDetector(context);

        View layout = inflater.inflate(R.layout.main_fragments, null);
        // Check if Internet present
        if (!cd.isConnectingToInternet()) {
            Crouton.makeText(context, "No internet connection", Style.ALERT);
        }

        dataSource = new HomeDataSource(context);
        dataSource.open();

        listView = (AsyncListView) layout.findViewById(R.id.listView);

        // Now find the PullToRefreshLayout to setup
        mPullToRefreshLayout = (PullToRefreshLayout) layout.findViewById(R.id.ptr_layout);

        // Now setup the PullToRefreshLayout
        ActionBarPullToRefresh.from(context)
                // Mark All Children as pullable
                .allChildrenArePullable()
                        // Set the OnRefreshListener
                .listener(this)
                        // Finally commit the setup to our PullToRefreshLayout
                .setup(mPullToRefreshLayout);

        BitmapLruCache cache = App.getInstance(context).getBitmapCache();
        CursorListLoader loader = new CursorListLoader(cache, context);

        ItemManager.Builder builder = new ItemManager.Builder(loader);
        builder.setPreloadItemsEnabled(true).setPreloadItemsCount(50);
        builder.setThreadPoolSize(4);

        listView.setItemManager(builder.build());

        new GetCursorAdapter().execute();

        if(settings.refreshOnStart && MainActivity.refreshMe) {
            
            final View view = layout;

            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    mPullToRefreshLayout.setRefreshing(true);
                    onRefreshStarted(view);
                }
            }, 400);

            MainActivity.refreshMe = false;
        }

        return layout;
    }

    @Override
    public void onRefreshStarted(final View view) {
        new AsyncTask<Void, Void, Void>() {

            private boolean update;
            private int numberNew;

            @Override
            protected Void doInBackground(Void... params) {
                try {
                    twitter = Utils.getTwitter(context);

                    User user = twitter.verifyCredentials();
                    long lastId = sharedPrefs.getLong("last_tweet_id", 0);
                    long secondToLastId = sharedPrefs.getLong("second_last_tweet_id", 0);
                    Paging paging = new Paging(1, 50);
                    List<twitter4j.Status> statuses = twitter.getHomeTimeline(paging);

                    boolean broken = false;

                    // first try to get the top 50 tweets
                    for (int i = 0; i < statuses.size(); i++) {
                        long id = statuses.get(i).getId();
                        if (id == lastId || id == secondToLastId) {
                            statuses = statuses.subList(0, i);
                            broken = true;
                            break;
                        }
                    }

                    // if that doesn't work, then go for the top 150
                    if (!broken) {
                        Paging paging2 = new Paging(1, 150);
                        List<twitter4j.Status> statuses2 = twitter.getHomeTimeline(paging2);

                        for (int i = 0; i < statuses2.size(); i++) {
                            long id = statuses2.get(i).getId();
                            if (id == lastId || id == secondToLastId) {
                                statuses2 = statuses2.subList(0, i);
                                break;
                            }
                        }

                        statuses = statuses2;
                    }

                    if (statuses.size() != 0) {
                        try {
                            sharedPrefs.edit().putLong("second_last_tweet_id", statuses.get(1).getId()).commit();
                        } catch (Exception e) {
                            sharedPrefs.edit().putLong("second_last_tweet_id", sharedPrefs.getLong("last_tweet_id", 0)).commit();
                        }
                        sharedPrefs.edit().putLong("last_tweet_id", statuses.get(0).getId()).commit();

                        update = true;
                        numberNew = statuses.size();
                    } else {
                        update = false;
                        numberNew = 0;
                    }

                    for (twitter4j.Status status : statuses) {
                        try {
                            dataSource.createTweet(status);
                        } catch (Exception e) {
                            e.printStackTrace();
                            break;
                        }
                    }

                } catch (TwitterException e) {
                    // Error in updating status
                    Log.d("Twitter Update Error", e.getMessage());
                }

                if (settings.timelineRefresh != 0) { // user only wants manual
                    AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

                    long now = new Date().getTime();
                    long alarm = now + settings.timelineRefresh;

                    Log.v("alarm_date", "timeline " + new Date(alarm).toString());

                    PendingIntent pendingIntent = PendingIntent.getService(context, HOME_REFRESH_ID, new Intent(context, TimelineRefreshService.class), 0);

                    am.setRepeating(AlarmManager.RTC_WAKEUP, alarm, settings.timelineRefresh, pendingIntent);
                }

                return null;
            }

            @Override
            protected void onPostExecute(Void result) {
                super.onPostExecute(result);

                if (update) {
                    cursorAdapter = new TimeLineCursorAdapter(context, dataSource.getCursor(), false);
                    refreshCursor();
                    CharSequence text = numberNew == 1 ?  numberNew + " " + getResources().getString(R.string.new_tweet) :  numberNew + " " + getResources().getString(R.string.new_tweets);
                    Crouton.makeText((Activity) context, text, Style.INFO).show();
                    listView.setSelectionFromTop(numberNew + 1, toDP(5));
                } else {
                    cursorAdapter = new TimeLineCursorAdapter(context, dataSource.getCursor(), false);
                    refreshCursor();

                    CharSequence text = getResources().getString(R.string.no_new_tweets);
                    Crouton.makeText((Activity) context, text, Style.INFO).show();
                }

                new RefreshMentions().execute();
            }
        }.execute();
    }

    class RefreshMentions extends AsyncTask<Void, Void, Boolean> {

        private boolean update = false;
        private int numberNew = 0;

        protected Boolean doInBackground(Void... args) {

            try {
                twitter = Utils.getTwitter(context);

                User user = twitter.verifyCredentials();
                long lastId = sharedPrefs.getLong("last_mention_id", 0);
                Paging paging;
                paging = new Paging(1, 50);

                List<twitter4j.Status> statuses = twitter.getMentionsTimeline(paging);

                boolean broken = false;

                // first try to get the top 50 tweets
                for (int i = 0; i < statuses.size(); i++) {
                    if (statuses.get(i).getId() == lastId) {
                        statuses = statuses.subList(0, i);
                        broken = true;
                        break;
                    }
                }

                // if that doesn't work, then go for the top 150
                if (!broken) {
                    Log.v("updating_timeline", "not broken");
                    Paging paging2 = new Paging(1, 150);
                    List<twitter4j.Status> statuses2 = twitter.getHomeTimeline(paging2);

                    for (int i = 0; i < statuses2.size(); i++) {
                        if (statuses2.get(i).getId() == lastId) {
                            statuses2 = statuses2.subList(0, i);
                            break;
                        }
                    }

                    statuses = statuses2;
                }

                if (statuses.size() != 0) {
                    sharedPrefs.edit().putLong("last_mention_id", statuses.get(0).getId()).commit();
                    update = true;
                    numberNew = statuses.size();
                } else {
                    update = false;
                    numberNew = 0;
                }

                MentionsDataSource dataSource = new MentionsDataSource(context);
                dataSource.open();

                Log.v("timeline_update", "Showing @" + user.getScreenName() + "'s home timeline.");
                for (twitter4j.Status status : statuses) {
                    try {
                        dataSource.createTweet(status);
                    } catch (Exception e) {
                        break;
                    }
                }

                dataSource.close();

            } catch (TwitterException e) {
                // Error in updating status
                Log.d("Twitter Update Error", e.getMessage());
            }

            return update;
        }

        protected void onPostExecute(Boolean updated) {

            if (updated) {
                CharSequence text = numberNew == 1 ?  numberNew + " " + getResources().getString(R.string.new_mention) :  numberNew + " " + getResources().getString(R.string.new_mentions);
                Crouton.makeText(context, text, Style.INFO).show();
                listView.setSelectionFromTop(numberNew + 1, toDP(5));
            } else {

            }

            mPullToRefreshLayout.setRefreshComplete();
        }

    }

    class GetCursorAdapter extends AsyncTask<Void, Void, String> {

        protected String doInBackground(Void... args) {

            cursorAdapter = new TimeLineCursorAdapter(context, dataSource.getCursor(), false);

            return null;
        }

        protected void onPostExecute(String file_url) {

            attachCursor(true);
        }

    }

    public void swapCursors() {
        cursorAdapter.swapCursor(dataSource.getCursor());
        cursorAdapter.notifyDataSetChanged();
    }

    public void refreshCursor() {
        try {
            listView.setAdapter(cursorAdapter);
        } catch (Exception e) {

        }

        swapCursors();
    }

    @Override
    public void onPause() {
        sharedPrefs.edit().putInt("timeline_unread", listView.getFirstVisiblePosition()).commit();

        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();

        try {
            attachCursor(false);
        } catch (Exception e) {

        }
    }

    @SuppressWarnings("deprecation")
    public void attachCursor(boolean header) {
        listView.setAdapter(cursorAdapter);

        swapCursors();

        LinearLayout viewHeader = new LinearLayout(context);
        viewHeader.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT, toDP(0));
        viewHeader.setLayoutParams(lp);

        try {
            if (header) {
                listView.addHeaderView(viewHeader, null, false);
            }
        } catch (Exception e) {

        }

        int newTweets = sharedPrefs.getInt("timeline_new", 0);

        if (newTweets > 0) {
            listView.setSelectionFromTop(newTweets + 1, toDP(5));
            sharedPrefs.edit().putInt("timeline_new", 0).commit();
        } else {
            int unread = sharedPrefs.getInt("timeline_unread", 0);

            if (unread > 0) {
                listView.setSelectionFromTop(unread + 1, toDP(5));
                sharedPrefs.edit().putInt("timeline_unread", 0).commit();
            }
        }
    }

    public int toDP(int px) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, px, getResources().getDisplayMetrics());
    }

}