/**
 *  Catroid: An on-device visual programming system for Android devices
 *  Copyright (C) 2010-2013 The Catrobat Team
 *  (<http://developer.catrobat.org/credits>)
 *  
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *  
 *  An additional term exception under section 7 of the GNU Affero
 *  General Public License, version 3, is available at
 *  http://developer.catrobat.org/license_additional_term
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU Affero General Public License for more details.
 *  
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.catrobat.catroid.ui.fragment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;

import org.catrobat.catroid.ProjectManager;
import org.catrobat.catroid.R;
import org.catrobat.catroid.common.Constants;
import org.catrobat.catroid.common.SoundInfo;
import org.catrobat.catroid.io.StorageHandler;
import org.catrobat.catroid.ui.ScriptActivity;
import org.catrobat.catroid.ui.adapter.SoundAdapter;
import org.catrobat.catroid.ui.adapter.SoundAdapter.OnSoundEditListener;
import org.catrobat.catroid.ui.adapter.SpriteAdapter;
import org.catrobat.catroid.ui.dialogs.DeleteSoundDialog;
import org.catrobat.catroid.ui.dialogs.RenameSoundDialog;
import org.catrobat.catroid.utils.ErrorListenerInterface;
import org.catrobat.catroid.utils.Utils;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ListView;

import com.actionbarsherlock.view.ActionMode;
import com.actionbarsherlock.view.Menu;

public class SoundFragment extends ScriptActivityFragment implements OnSoundEditListener,
		LoaderManager.LoaderCallbacks<Cursor> {

	private class CopyAudioFilesTask extends AsyncTask<String, Void, File> {
		private ProgressDialog progressDialog = new ProgressDialog(getActivity());

		@Override
		protected void onPreExecute() {
			progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
			progressDialog.setTitle(getString(R.string.loading));
			progressDialog.show();
		}

		@Override
		protected File doInBackground(String... path) {
			File file = null;
			try {
				file = StorageHandler.getInstance().copySoundFile(path[0]);
			} catch (IOException e) {
				Log.e("CATROID", "Cannot load sound.", e);
			}
			return file;
		}

		@Override
		protected void onPostExecute(File file) {
			progressDialog.dismiss();

			if (file != null) {
				String fileName = file.getName();
				String soundTitle = fileName.substring(fileName.indexOf('_') + 1, fileName.lastIndexOf('.'));
				updateSoundAdapter(soundTitle, fileName);
			} else {
				Utils.displayErrorMessageFragment(getActivity().getSupportFragmentManager(),
						getString(R.string.error_load_sound));
			}
		}
	}

	private static final String BUNDLE_ARGUMENTS_SELECTED_SOUND = "selected_sound";
	private static final String SHARED_PREFERENCE_NAME = "showDetailsSounds";
	private static final int ID_LOADER_MEDIA_IMAGE = 1;

	public static final int REQUEST_SELECT_MUSIC = 0;

	private MediaPlayer mediaPlayer;
	private SoundAdapter adapter;
	private ArrayList<SoundInfo> soundInfoList;

	private SoundInfo selectedSoundInfo;

	private static int currentSoundPosition = Constants.NO_POSITION;

	private View currentPlayingView = null;

	private ListView listView;

	private SoundDeletedReceiver soundDeletedReceiver;
	private SoundRenamedReceiver soundRenamedReceiver;

	private ActionMode actionMode;
	private static int numberOfSelectedItems = 0;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setHasOptionsMenu(true);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View rootView = inflater.inflate(R.layout.fragment_sound, null);
		return rootView;
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);

		listView = getListView();
		registerForContextMenu(listView);

		if (savedInstanceState != null) {
			selectedSoundInfo = (SoundInfo) savedInstanceState.getSerializable(BUNDLE_ARGUMENTS_SELECTED_SOUND);
		}

		soundInfoList = ProjectManager.getInstance().getCurrentSprite().getSoundList();

		adapter = new SoundAdapter(getActivity(), R.layout.fragment_sound_soundlist_item, soundInfoList, false);
		adapter.setOnSoundEditListener(this);
		setListAdapter(adapter);

		try {
			Utils.loadProjectIfNeeded(getActivity(), (ErrorListenerInterface) getActivity());
		} catch (ClassCastException exception) {
			Log.e("CATROID", getActivity().toString() + " does not implement ErrorListenerInterface", exception);
		}
	}

	@Override
	public void onPrepareOptionsMenu(Menu menu) {
		menu.findItem(R.id.show_details).setVisible(true);
		super.onPrepareOptionsMenu(menu);
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		outState.putSerializable(BUNDLE_ARGUMENTS_SELECTED_SOUND, selectedSoundInfo);
		super.onSaveInstanceState(outState);
	}

	@Override
	public void onStart() {
		super.onStart();
		mediaPlayer = new MediaPlayer();
		initClickListener();
	}

	@Override
	public void onResume() {
		super.onResume();

		if (!Utils.checkForExternalStorageAvailableAndDisplayErrorIfNot(getActivity())) {
			return;
		}

		if (soundRenamedReceiver == null) {
			soundRenamedReceiver = new SoundRenamedReceiver();
		}

		if (soundDeletedReceiver == null) {
			soundDeletedReceiver = new SoundDeletedReceiver();
		}

		IntentFilter intentFilterRenameSound = new IntentFilter(ScriptActivity.ACTION_SOUND_RENAMED);
		getActivity().registerReceiver(soundRenamedReceiver, intentFilterRenameSound);

		IntentFilter intentFilterDeleteSound = new IntentFilter(ScriptActivity.ACTION_SOUND_DELETED);
		getActivity().registerReceiver(soundDeletedReceiver, intentFilterDeleteSound);

		stopSound();

		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getActivity()
				.getApplicationContext());

		setShowDetails(settings.getBoolean(SHARED_PREFERENCE_NAME, false));
	}

	@Override
	public void onPause() {
		super.onPause();

		ProjectManager projectManager = ProjectManager.getInstance();
		if (projectManager.getCurrentProject() != null) {
			projectManager.saveProject();
		}
		stopSound();
		adapter.notifyDataSetChanged();

		if (soundRenamedReceiver != null) {
			getActivity().unregisterReceiver(soundRenamedReceiver);
		}

		if (soundDeletedReceiver != null) {
			getActivity().unregisterReceiver(soundDeletedReceiver);
		}

		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getActivity()
				.getApplicationContext());
		SharedPreferences.Editor editor = settings.edit();

		editor.putBoolean(SHARED_PREFERENCE_NAME, getShowDetails());
		editor.commit();
	}

	@Override
	public void onStop() {
		super.onStop();
		mediaPlayer.reset();
		mediaPlayer.release();
		mediaPlayer = null;
	}

	private ActionMode.Callback renameModeCallBack = new ActionMode.Callback() {

		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			return false;
		}

		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			setSelectMode(SpriteAdapter.SINGLE_SELECT);
			mode.setTitle(getString(R.string.rename));

			setActionModeActive(true);

			return true;
		}

		@Override
		public boolean onActionItemClicked(ActionMode mode, com.actionbarsherlock.view.MenuItem item) {
			return false;
		}

		@Override
		public void onDestroyActionMode(ActionMode mode) {
			Set<Integer> checkedSounds = adapter.getCheckedItems();
			Iterator<Integer> iterator = checkedSounds.iterator();

			if (iterator.hasNext()) {
				int position = iterator.next();
				selectedSoundInfo = (SoundInfo) getListView().getItemAtPosition(position);
				showRenameDialog();
			}
			setSelectMode(Constants.SELECT_NONE);
			adapter.clearCheckedItems();
			actionMode = null;

			setActionModeActive(false);
		}
	};

	private ActionMode.Callback deleteModeCallBack = new ActionMode.Callback() {

		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			mode.setTitle(getString(R.string.delete));
			return false;
		}

		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			setSelectMode(Constants.MULTI_SELECT);

			setActionModeActive(true);

			return true;
		}

		@Override
		public boolean onActionItemClicked(ActionMode mode, com.actionbarsherlock.view.MenuItem item) {
			return false;
		}

		@Override
		public void onDestroyActionMode(ActionMode mode) {
			Set<Integer> checkedSounds = adapter.getCheckedItems();
			Iterator<Integer> iterator = checkedSounds.iterator();

			int numberDeleted = 0;

			while (iterator.hasNext()) {
				int position = iterator.next();
				deleteSound(position - numberDeleted);
				++numberDeleted;
			}
			setSelectMode(Constants.SELECT_NONE);
			adapter.clearCheckedItems();

			actionMode = null;

			numberOfSelectedItems = 0;

			setActionModeActive(false);
		}
	};

	@Override
	public void startRenameActionMode() {
		if (actionMode == null) {
			actionMode = getSherlockActivity().startActionMode(renameModeCallBack);
		}
	}

	@Override
	public void startDeleteActionMode() {
		if (actionMode == null) {
			actionMode = getSherlockActivity().startActionMode(deleteModeCallBack);
		}
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		//when new sound title is selected and ready to be added to the catroid project
		if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_SELECT_MUSIC && data != null) {
			Bundle arguments = new Bundle();
			arguments.putParcelable(BUNDLE_ARGUMENTS_SELECTED_SOUND, data.getData());

			if (getLoaderManager().getLoader(ID_LOADER_MEDIA_IMAGE) == null) {
				getLoaderManager().initLoader(ID_LOADER_MEDIA_IMAGE, arguments, this);
			} else {
				getLoaderManager().restartLoader(ID_LOADER_MEDIA_IMAGE, arguments, this);
			}
		}
	}

	@Override
	public void onSoundPlay(View v) {
		handlePlaySoundButton(v);
	}

	@Override
	public void onSoundPause(View v) {
		handlePauseSoundButton(v);
	}

	@Override
	public void onSoundChecked(boolean isChecked) {
		if (isChecked) {
			++numberOfSelectedItems;
		} else {
			--numberOfSelectedItems;
		}

		String title = getString(R.string.delete);

		if (numberOfSelectedItems == 0) {
			actionMode.setTitle(title);
		} else {
			String appendix = getString(R.string.sounds);

			if (numberOfSelectedItems == 1) {
				appendix = getString(R.string.category_sound);
			}

			String numberOfItems = Integer.toString(numberOfSelectedItems);
			String completeTitle = title + " " + numberOfItems + " " + appendix;

			int titleLength = title.length();

			Spannable completeSpannedTitle = new SpannableString(completeTitle);
			completeSpannedTitle.setSpan(
					new ForegroundColorSpan(getResources().getColor(R.color.actionbar_title_color)), titleLength + 1,
					titleLength + (numberOfItems.length() + 1), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

			actionMode.setTitle(completeSpannedTitle);
		}
	}

	@Override
	public Loader<Cursor> onCreateLoader(int id, Bundle arguments) {
		Uri audioUri = null;

		if (arguments != null) {
			audioUri = (Uri) arguments.get(BUNDLE_ARGUMENTS_SELECTED_SOUND);
		}
		String[] projection = { MediaStore.Audio.Media.DATA };
		return new CursorLoader(getActivity(), audioUri, projection, null, null, null);
	}

	@Override
	public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
		String audioPath = "";
		CursorLoader cursorLoader = (CursorLoader) loader;

		if (data == null) {
			audioPath = cursorLoader.getUri().getPath();
		} else {
			data.moveToFirst();
			audioPath = data.getString(data.getColumnIndex(MediaStore.Audio.Media.DATA));
		}

		if (audioPath.equalsIgnoreCase("")) {
			Utils.displayErrorMessageFragment(getActivity().getSupportFragmentManager(),
					getString(R.string.error_load_sound));
		} else {
			new CopyAudioFilesTask().execute(audioPath);
		}

		getLoaderManager().destroyLoader(ID_LOADER_MEDIA_IMAGE);
		getActivity().sendBroadcast(new Intent(ScriptActivity.ACTION_BRICK_LIST_CHANGED));
	}

	@Override
	public void onLoaderReset(Loader<Cursor> loader) {
	}

	private void updateSoundAdapter(String title, String fileName) {
		title = Utils.getUniqueSoundName(title);

		SoundInfo newSoundInfo = new SoundInfo();
		newSoundInfo.setTitle(title);
		newSoundInfo.setSoundFileName(fileName);
		soundInfoList.add(newSoundInfo);
		adapter.notifyDataSetChanged();

		//scroll down the list to the new item:
		{
			final ListView listView = getListView();
			listView.post(new Runnable() {
				@Override
				public void run() {
					listView.setSelection(listView.getCount() - 1);
				}
			});
		}
	}

	public void handlePlaySoundButton(View v) {
		final int position = (Integer) v.getTag();
		final SoundInfo soundInfo = soundInfoList.get(position);

		stopSound();
		if (!soundInfo.isPlaying) {
			startSound(soundInfo);
			currentPlayingView = v;
			adapter.notifyDataSetChanged();
		}

		mediaPlayer.setOnCompletionListener(new OnCompletionListener() {
			@Override
			public void onCompletion(MediaPlayer mp) {
				soundInfo.isPlaying = false;
				adapter.notifyDataSetChanged();
				currentPlayingView = null;
			}
		});
	}

	public void handlePauseSoundButton(View v) {
		final int position = (Integer) v.getTag();
		pauseSound(soundInfoList.get(position));
		adapter.notifyDataSetChanged();
		currentPlayingView = null;
	}

	public void pauseSound(SoundInfo soundInfo) {
		mediaPlayer.pause();
		soundInfo.isPlaying = false;
	}

	public void stopSound() {
		if (mediaPlayer.isPlaying()) {
			mediaPlayer.stop();
		}

		for (int i = 0; i < soundInfoList.size(); i++) {
			soundInfoList.get(i).isPlaying = false;
		}
	}

	public void startSound(SoundInfo soundInfo) {
		if (!soundInfo.isPlaying) {
			try {
				mediaPlayer.reset();
				mediaPlayer.setDataSource(soundInfo.getAbsolutePath());
				mediaPlayer.prepare();
				mediaPlayer.start();

				soundInfo.isPlaying = true;
			} catch (IOException e) {
				Log.e("CATROID", "Cannot start sound.", e);
			}
		}
	}

	private void initClickListener() {
		listView.setOnItemLongClickListener(new OnItemLongClickListener() {
			@Override
			public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
				currentSoundPosition = position;
				return false;
			}
		});
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
		super.onCreateContextMenu(menu, v, menuInfo);

		if (currentPlayingView != null) {
			handlePauseSoundButton(currentPlayingView);
		}
		selectedSoundInfo = adapter.getItem(currentSoundPosition);
		menu.setHeaderTitle(selectedSoundInfo.getTitle());

		getSherlockActivity().getMenuInflater().inflate(R.menu.context_menu_default, menu);
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.copy:
				break;

			case R.id.cut:
				break;

			case R.id.insert_below:
				break;

			case R.id.move:
				break;

			case R.id.rename:
				showRenameDialog();
				break;

			case R.id.delete:
				showDeleteDialog();
				break;
		}
		return super.onContextItemSelected(item);
	}

	@Override
	public void setSelectMode(int selectMode) {
		adapter.setSelectMode(selectMode);
		adapter.notifyDataSetChanged();
	}

	@Override
	public int getSelectMode() {
		return adapter.getSelectMode();
	}

	@Override
	public void setShowDetails(boolean showDetails) {
		// TODO CHANGE THIS!!! (was just a quick fix)
		if (adapter != null) {
			adapter.setShowDetails(showDetails);
			adapter.notifyDataSetChanged();
		}
	}

	@Override
	public boolean getShowDetails() {
		// TODO CHANGE THIS!!! (was just a quick fix)
		if (adapter != null) {
			return adapter.getShowDetails();
		} else {
			return false;
		}
	}

	private void deleteSound(int position) {
		StorageHandler.getInstance().deleteFile(soundInfoList.get(position).getAbsolutePath());

		soundInfoList.remove(position);
		ProjectManager.getInstance().getCurrentSprite().setSoundList(soundInfoList);

		getActivity().sendBroadcast(new Intent(ScriptActivity.ACTION_SOUND_DELETED));
	}

	@Override
	protected void showRenameDialog() {
		RenameSoundDialog renameSoundDialog = RenameSoundDialog.newInstance(selectedSoundInfo.getTitle());
		renameSoundDialog.show(getFragmentManager(), RenameSoundDialog.DIALOG_FRAGMENT_TAG);
	}

	@Override
	protected void showDeleteDialog() {
		if (currentSoundPosition != Constants.NO_POSITION) {
			DeleteSoundDialog deleteSoundDialog = DeleteSoundDialog.newInstance(currentSoundPosition);
			deleteSoundDialog.show(getFragmentManager(), DeleteSoundDialog.DIALOG_FRAGMENT_TAG);
		} else {
			Log.e("CATROID", "No sound selected!");
		}
	}

	private class SoundRenamedReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent.getAction().equals(ScriptActivity.ACTION_SOUND_RENAMED)) {
				String newSoundTitle = intent.getExtras().getString(RenameSoundDialog.EXTRA_NEW_SOUND_TITLE);

				if (newSoundTitle != null && !newSoundTitle.equalsIgnoreCase("")) {
					selectedSoundInfo.setTitle(newSoundTitle);
					adapter.notifyDataSetChanged();
				}
			}
		}
	}

	private class SoundDeletedReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent.getAction().equals(ScriptActivity.ACTION_SOUND_DELETED)) {
				adapter.notifyDataSetChanged();
				getActivity().sendBroadcast(new Intent(ScriptActivity.ACTION_BRICK_LIST_CHANGED));
			}
		}
	}

	@Override
	public void handleAddButton() {
		Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
		intent.setType("audio/*");

		startActivityForResult(Intent.createChooser(intent, getString(R.string.sound_select_source)),
				REQUEST_SELECT_MUSIC);
	}
}
