package org.fasola.fasolaminutes;

import android.content.Intent;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

public class MainActivity extends SimpleTabActivity {
    public final static String ACTIVITY_POSITION = "org.fasola.fasolaminutes.POSITION";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (PlaybackService.isRunning())
            PlaybackService.getInstance().setMainTaskRunning(true);
        setContentView(R.layout.activity_main);
        // Save all the pages since the queries may take some time to run
        mViewPager.setOffscreenPageLimit(mPagerAdapter.getCount());
        // Set page change listener and initial settings
        setOnPageChangeListener(mPageChangeListener);
        handleIntent(getIntent());
    }

    // Change title and FaSoLa tabs when the page changes
    ViewPager.SimpleOnPageChangeListener mPageChangeListener =
        new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                setTitle(mPagerAdapter.getPageTitle(position));
                ((FasolaTabView) findViewById(R.id.fasola_tabs)).setSelection(position);
            }
        };

    @Override
    protected void onResume() {
        mPageChangeListener.onPageSelected(mViewPager.getCurrentItem());
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        if (PlaybackService.isRunning())
            PlaybackService.getInstance().setMainTaskRunning(false);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    protected void handleIntent(Intent intent) {
        // Change to the requested fragment (by position)
        int position = intent.getIntExtra(ACTIVITY_POSITION, -1);
        if (position != -1) {
            // Change page, or at least force the Activity to think the page was
            // changed so it updates fasola tabs and title
            if (mViewPager.getCurrentItem() != position)
                mViewPager.setCurrentItem(position, true);
            else
                mPageChangeListener.onPageSelected(position);
        }
        // Recordings deep link:
        // fasola://recordings?singing=singingId
        Uri data = intent.getData();
        if (data != null &&
                "fasola".equals(data.getScheme()) &&
                "recordings".equals(data.getHost())) {
            // Got a recordings url
            if (data.getLastPathSegment().equals("random")) {
                // Start a random singing
                SQL.Query query = SQL.select(C.Singing.id)
                                     .where(C.Singing.recordingCount, ">", "10")
                                     .order("RANDOM()")
                                     .limit(1);
                long singingId = MinutesDb.getInstance().queryLong(query.toString());
                if (singingId > -1)
                    PlaybackService.playSinging(this, PlaybackService.ACTION_PLAY_MEDIA, singingId);
            }
            else {
                try {
                    // Start the specified singing
                    long singingId = Long.parseLong(data.getQueryParameter("singing"));
                    PlaybackService.playSinging(this, PlaybackService.ACTION_PLAY_MEDIA, singingId);
                } catch (NumberFormatException | UnsupportedOperationException ex) {
                    Log.w("MainActivity", "Bad url: " + data.toString());
                }
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    public static class LeaderListFragment extends CursorStickyListFragment {
        protected int mSortId = R.id.menu_leader_sort_name;
        protected final static String BUNDLE_SORT = "SORT_ID";

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            if (savedInstanceState != null)
                mSortId = savedInstanceState.getInt(BUNDLE_SORT, mSortId);
            setHasOptionsMenu(true);
        }

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            setIntentActivity(LeaderActivity.class);
            setItemLayout(R.layout.list_item_leader);
            updateQuery();
        }

        @Override
        public void onSaveInstanceState(final Bundle saveInstanceState) {
            super.onSaveInstanceState(saveInstanceState);
            saveInstanceState.putSerializable(BUNDLE_SORT, mSortId);
        }

        @Override
        public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
            inflater.inflate(R.menu.menu_leader_list_fragment, menu);
            // Check the initial sort
            MenuItem item = menu.findItem(mSortId);
            if (item != null)
                item.setChecked(true);
        }

        @Override
        public boolean onOptionsItemSelected(MenuItem item) {
            // Sort
            if (item.getGroupId() == R.id.menu_group_sort) {
                item.setChecked(true);
                mSortId = item.getItemId();
                updateQuery();
                return true;
            }
            return super.onOptionsItemSelected(item);
        }

        // Change query/index based on the selected sort column

        @Override
        public SQL.Query onUpdateQuery() {
            switch(mSortId) {
                case R.id.menu_leader_sort_count:
                    setBinCount(7);
                    showHeaders(false);
                    return C.Leader.selectList(C.Leader.fullName, C.Leader.leadCount.format("'(' || {column} || ')'"))
                                   .sectionIndex(C.Leader.leadCount, "DESC")
                                   .order(C.Leader.lastName, "ASC", C.Leader.fullName, "ASC");
                case R.id.menu_leader_sort_entropy:
                    setBins(0, 10, 20, 30, 40, 50, 60, 70, 80, 90);
                    setSectionLabels("0", "0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9");
                    showHeaders(false);
                    return C.Leader.selectList(C.Leader.fullName, C.Leader.entropyDisplay.format("'(' || {column} || ')'"))
                                   .sectionIndex(C.Leader.entropy.format("CAST({column} * 100 AS INT)"), "DESC")
                                   .order(C.Leader.entropy, "DESC",
                                           C.Leader.lastName, "ASC",
                                           C.Leader.fullName, "ASC");
                case R.id.menu_leader_sort_first_name:
                    setAlphabetIndexer();
                    showHeaders(true);
                    return C.Leader.selectList(C.Leader.fullName, C.Leader.leadCount.format("'(' || {column} || ')'"))
                                   .sectionIndex(C.Leader.fullName, "ASC");
                case R.id.menu_leader_sort_name:
                default:
                    setAlphabetIndexer();
                    showHeaders(true);
                    return C.Leader.selectList(C.Leader.fullName, C.Leader.leadCount.format("'(' || {column} || ')'"))
                                   .sectionIndex(C.Leader.lastName, "ASC")
                                   .order(C.Leader.fullName, "ASC");
            }
        }

        @Override
        public SQL.Query onUpdateSearch(SQL.Query query, String searchTerm) {
            return query.where(C.Leader.fullName, "LIKE", "%" + searchTerm + "%")
                        .or(C.LeaderAlias.alias, "LIKE", "%" + searchTerm + "%");
        }
    }

    public static class SongListFragment extends CursorStickyListFragment {
        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            setMenuResource(R.menu.menu_song_list_fragment);
            setDefaultSortId(R.id.menu_song_sort_page);
            setIntentActivity(SongActivity.class);
            setItemLayout(R.layout.list_item_song);
            updateQuery();
        }

        /**
         * Get the default query for filtering or sorting
         * @return {SQL.Query} Default song query
         */
        private SQL.Query songQuery() {
            return C.Song.selectList(C.Song.number, C.Song.title,
                                     C.SongStats.leadCount.sum().format("'(' || {column} || ')'"));
        }

        // Code common to onUpdateQuery and onUpdateSearch
        private SQL.Query setQueryOrder(SQL.Query query, boolean setSectionIndex) {
            switch(getSortId()) {
                case R.id.menu_song_sort_title:
                    if (setSectionIndex) {
                        setAlphabetIndexer();
                        showHeaders(true);
                        return query.sectionIndex(C.Song.title, "ASC");
                    }
                    else
                        return query.order(C.Song.title, "ASC");
                case R.id.menu_song_sort_leads:
                    if (setSectionIndex) {
                        setBinCount(7);
                        showHeaders(false);
                        return query.sectionIndex(C.SongStats.leadCount.sum(), "DESC");
                    }
                    else
                        return query.order(C.SongStats.leadCount.sum(), "DESC");
                case R.id.menu_song_sort_key:
                    if (setSectionIndex) {
                        setStringIndexer();
                        showHeaders(true);
                        return query.sectionIndex(C.Song.key, "ASC");
                    }
                    else
                        return query.order(C.Song.key, "ASC");
                case R.id.menu_song_sort_time:
                    if (setSectionIndex) {
                        setStringIndexer();
                        showHeaders(true);
                        return query.sectionIndex(C.Song.time, "ASC");
                    }
                    else
                        return query.order(C.Song.time, "ASC");
                case R.id.menu_song_sort_meter:
                    if (setSectionIndex) {
                        setStringIndexer();
                        showHeaders(true);
                        return query.sectionIndex(C.Song.meter)
                                    .orderAsc(C.Song.meter.cast("INT"))
                                    .orderAsc(C.Song.meter);
                    }
                    else
                        return query.orderAsc(C.Song.meter.cast("INT"))
                                    .orderAsc(C.Song.meter);
                case R.id.menu_song_sort_page:
                default:
                    if (setSectionIndex) {
                        setBins(0, 100, 200, 300, 400, 500);
                        showHeaders(false);
                        return query.sectionIndex(C.Song.pageSort);
                    }
                    else
                        return query.order(C.Song.pageSort, "ASC");
            }
        }

        // Change query/index based on the selected sort column
        public SQL.Query onUpdateQuery() {
            return setQueryOrder(songQuery(), true);
        }

        @Override
        public SQL.Query onUpdateSearch(SQL.Query query, String searchTerm) {
            searchTerm = DatabaseUtils.sqlEscapeString("%" + searchTerm + "%");
            showHeaders(true);
            setAlphabet("0123");
            setSectionLabels("Title", "Composer", "Poet", "Words");
            query = songQuery().sectionIndex(
                    new SQL.QueryColumn(
                            "CASE ",
                            C.Song.fullName.format("WHEN {column} LIKE %s THEN 0 ", searchTerm),
                            C.Song.composer.format("WHEN {column} LIKE %s THEN 1 ", searchTerm),
                            C.Song.poet.format("WHEN {column} LIKE %s THEN 2  ", searchTerm),
                            C.Song.lyrics.format("WHEN {column} LIKE %s THEN 3 ", searchTerm),
                            "END"
                    ), "ASC")
                .where(SQL.INDEX_COLUMN, "IS NOT", "NULL");
            // Add the additional order clause
            return setQueryOrder(query, false);
        }
    }

    public static class SingingListFragment extends CursorStickyListFragment {
        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            setMenuResource(R.menu.menu_singing_list_fragment);
            setDefaultSortId(R.id.menu_singing_sort_year);
            setIntentActivity(SingingActivity.class);
            setItemLayout(R.layout.list_item_singing);
            updateQuery();
        }

        /**
         * Get the default query for filtering or sorting
         * @return {SQL.Query} Default singing query
         */
        private SQL.Query singingQuery() {
            return C.Singing.selectList(C.Singing.name, C.Singing.startDate, C.Singing.location)
                            .select(C.Singing.recordingCount).as(CursorListFragment.AUDIO_COLUMN);
        }

        public SQL.Query onUpdateQuery() {
            switch(mSortId) {
                case R.id.menu_singing_sort_recordings:
                    showHeaders(false);
                    return singingQuery().where(C.Singing.recordingCount, ">", "0")
                                         .orderDesc(C.Singing.recordingCount)
                                         .orderAsc(C.Singing.year);
                case R.id.menu_singing_sort_year:
                default:
                    setRangeIndexer();
                    showHeaders(true);
                    return singingQuery().sectionIndex(C.Singing.year);
            }
        }

        @Override
        public SQL.Query onUpdateSearch(SQL.Query query, String searchTerm) {
            return query.where(C.Singing.name, "LIKE", "%" + searchTerm + "%")
                        .or(C.Singing.location, "LIKE", "%" + searchTerm + "%");
        }

        @Override
        public void onPlayClick(View v, int position) {
            Cursor cursor = getListAdapter().getCursor();
            cursor.moveToPosition(position);
            int singingId = cursor.getInt(0);
            PlaybackService.playSinging(getActivity(), PlaybackService.ACTION_PLAY_MEDIA, singingId);
        }

        @Override
        public boolean onPlayLongClick(View v, int position) {
            Cursor cursor = getListAdapter().getCursor();
            cursor.moveToPosition(position);
            int singingId = cursor.getInt(0);
            PlaybackService.playSinging(getActivity(), PlaybackService.ACTION_ENQUEUE_MEDIA, singingId);
            return true;
        }
    }
}