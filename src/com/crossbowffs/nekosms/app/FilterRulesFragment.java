package com.crossbowffs.nekosms.app;

import android.Manifest;
import android.app.LoaderManager;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.text.Editable;
import android.view.*;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import com.crossbowffs.nekosms.R;
import com.crossbowffs.nekosms.backup.BackupLoader;
import com.crossbowffs.nekosms.backup.ExportResult;
import com.crossbowffs.nekosms.backup.ImportResult;
import com.crossbowffs.nekosms.data.SmsFilterAction;
import com.crossbowffs.nekosms.data.SmsFilterData;
import com.crossbowffs.nekosms.loader.FilterRuleLoader;
import com.crossbowffs.nekosms.provider.DatabaseContract;
import com.crossbowffs.nekosms.utils.IOUtils;
import com.crossbowffs.nekosms.utils.Xlog;
import com.crossbowffs.nekosms.widget.DialogAsyncTask;
import com.crossbowffs.nekosms.widget.ListRecyclerView;
import com.crossbowffs.nekosms.widget.TextWatcherAdapter;

import java.io.File;

public class FilterRulesFragment extends MainFragment implements LoaderManager.LoaderCallbacks<Cursor> {
    private static final int IMPORT_BACKUP_REQUEST = 0;
    private static final int EXPORT_BACKUP_REQUEST = 1;
    public static final String EXTRA_ACTION = "action";

    private ListRecyclerView mRecyclerView;
    private TextView mEmptyView;
    private FilterRulesAdapter mAdapter;
    private SmsFilterAction mAction;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        mAction = (SmsFilterAction)getArguments().getSerializable(EXTRA_ACTION);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_filter_rules, container, false);
        mRecyclerView = (ListRecyclerView)view.findViewById(R.id.filter_rules_recyclerview);
        mEmptyView = (TextView)view.findViewById(android.R.id.empty);
        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        FilterRulesAdapter adapter = new FilterRulesAdapter(this);
        mAdapter = adapter;
        LoaderManager loaderManager = getLoaderManager();
        loaderManager.initLoader(0, null, this);
        mRecyclerView.setAdapter(adapter);
        mRecyclerView.setEmptyView(mEmptyView);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        registerForContextMenu(mRecyclerView);
        enableFab(R.drawable.ic_create_white_24dp, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getContext(), FilterEditorActivity.class);
                intent.putExtra(EXTRA_ACTION, mAction);
                startActivity(intent);
            }
        });
        if (mAction == SmsFilterAction.BLOCK) {
            setTitle(R.string.blacklist_rules);
            mEmptyView.setText(R.string.blacklist_rules_empty_text);
        } else if (mAction == SmsFilterAction.ALLOW) {
            setTitle(R.string.whitelist_rules);
            mEmptyView.setText(R.string.whitelist_rules_empty_text);
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        MenuInflater inflater = getActivity().getMenuInflater();
        inflater.inflate(R.menu.context_filter_rules, menu);
        menu.setHeaderTitle(R.string.filter_actions);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        ListRecyclerView.ContextMenuInfo info = (ListRecyclerView.ContextMenuInfo)item.getMenuInfo();
        switch (item.getItemId()) {
        case R.id.menu_item_edit_filter:
            startFilterEditorActivity(info.mId);
            return true;
        case R.id.menu_item_delete_filter:
            deleteFilter(info.mId);
            return true;
        default:
            return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.options_filter_rules, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.menu_item_import_export_filters:
            showImportExportDialog();
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        return new CursorLoader(getContext(),
            DatabaseContract.FilterRules.CONTENT_URI,
            DatabaseContract.FilterRules.ALL,
            DatabaseContract.FilterRules.ACTION + "=?",
            new String[] {mAction.name()},
            null);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        mAdapter.changeCursor(data);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mAdapter.changeCursor(null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, boolean granted) {
        if (!granted) {
            showToast(R.string.need_storage_permission);
        } else if (requestCode == IMPORT_BACKUP_REQUEST) {
            showImportFileSelectionDialog();
        } else if (requestCode == EXPORT_BACKUP_REQUEST) {
            showExportFileNameDialog();
        }
    }

    private void showImportExportDialog() {
        CharSequence[] items = {getString(R.string.import_from_storage), getString(R.string.export_to_storage)};
        new AlertDialog.Builder(getContext())
            .setTitle(R.string.import_export)
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        requestPermissionsCompat(Manifest.permission.READ_EXTERNAL_STORAGE, IMPORT_BACKUP_REQUEST);
                    } else if (which == 1) {
                        requestPermissionsCompat(Manifest.permission.WRITE_EXTERNAL_STORAGE, EXPORT_BACKUP_REQUEST);
                    }
                }
            })
            .show();
    }

    private void showImportFileSelectionDialog() {
        final File[] files = BackupLoader.enumerateBackupFiles();
        if (files == null || files.length == 0) {
            showToast(R.string.import_no_backup);
            return;
        }

        String[] fileNames = new String[files.length];
        for (int i = 0; i < fileNames.length; ++i) {
            fileNames[i] = files[i].getName();
        }

        new AlertDialog.Builder(getContext())
            .setTitle(R.string.import_choose_file)
            .setItems(fileNames, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    showConfirmImportDialog(files[which]);
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showConfirmImportDialog(final File file) {
        new AlertDialog.Builder(getContext())
            .setIcon(R.drawable.ic_warning_white_24dp)
            .setTitle(R.string.import_confirm_title)
            .setMessage(R.string.import_confirm_message)
            .setPositiveButton(R.string.backup_button_import, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    importFromStorage(file);
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showExportFileNameDialog() {
        String defaultName = BackupLoader.getDefaultBackupFileName();
        LayoutInflater layoutInflater = getActivity().getLayoutInflater();
        View dialogView = layoutInflater.inflate(R.layout.dialog_edittext, null);
        final EditText editText = (EditText)dialogView.findViewById(R.id.dialog_edittext_textbox);
        final AlertDialog dialog = new AlertDialog.Builder(getContext())
            .setTitle(R.string.export_file_name)
            .setView(dialogView)
            .setPositiveButton(R.string.backup_button_export, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String fileName = editText.getText().toString();
                    File file = new File(BackupLoader.getBackupDirectory(), fileName);
                    exportToStorage(file);
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
        editText.setText(defaultName);
        editText.setSelection(0, defaultName.length() - BackupLoader.getBackupFileExtension().length());
        editText.addTextChangedListener(new TextWatcherAdapter() {
            @Override
            public void afterTextChanged(Editable s) {
                Button button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                button.setEnabled(IOUtils.isValidFileName(s));
            }
        });
    }

    private void importFromStorage(final File file) {
        new DialogAsyncTask<Void, Void, ImportResult>(getContext(), R.string.progress_importing) {
            @Override
            protected ImportResult doInBackground(Void... params) {
                return BackupLoader.importFromStorage(getContext(), file);
            }

            @Override
            protected void onPostExecute(ImportResult result) {
                super.onPostExecute(result);
                int messageId;
                switch (result) {
                case SUCCESS:
                    messageId = R.string.import_success;
                    break;
                case UNKNOWN_VERSION:
                    messageId = R.string.import_unknown_version;
                    break;
                case INVALID_BACKUP:
                    messageId = R.string.import_invalid_backup;
                    break;
                case READ_FAILED:
                    messageId = R.string.import_read_failed;
                    break;
                case CANNOT_READ_STORAGE:
                    messageId = R.string.import_cannot_read_storage;
                    break;
                default:
                    throw new AssertionError("Unknown backup import result code: " + result);
                }
                showToast(messageId);
                showFabIfAutoHidden();
            }
        }.execute();
    }

    private void exportToStorage(final File file) {
        new DialogAsyncTask<Void, Void, ExportResult>(getContext(), R.string.progress_exporting) {
            @Override
            protected ExportResult doInBackground(Void... params) {
                return BackupLoader.exportToStorage(getContext(), file);
            }

            @Override
            protected void onPostExecute(ExportResult result) {
                super.onPostExecute(result);
                int messageId;
                switch (result) {
                case SUCCESS:
                    messageId = R.string.export_success;
                    break;
                case WRITE_FAILED:
                    messageId = R.string.export_write_failed;
                    break;
                case CANNOT_WRITE_STORAGE:
                    messageId = R.string.export_cannot_write_storage;
                    break;
                default:
                    throw new AssertionError("Unknown backup export result code: " + result);
                }

                if (result == ExportResult.SUCCESS) {
                    showSnackbar(messageId, R.string.share, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Context context = getContext();
                            if (context == null) return;
                            BackupLoader.shareBackupFile(context, file);
                        }
                    });
                } else {
                    showToast(messageId);
                }
            }
        }.execute();
    }

    private void deleteFilter(long filterId) {
        final SmsFilterData filterData = FilterRuleLoader.get().queryAndDelete(getContext(), filterId);
        if (filterData == null) {
            Xlog.e("Failed to delete filter: could not load data");
            return;
        }

        showFabIfAutoHidden();
        showSnackbar(R.string.filter_deleted, R.string.undo, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FilterRuleLoader.get().insert(getContext(), filterData);
            }
        });
    }

    public void startFilterEditorActivity(long id) {
        Intent intent = new Intent(getContext(), FilterEditorActivity.class);
        Uri filterUri = ContentUris.withAppendedId(DatabaseContract.FilterRules.CONTENT_URI, id);
        intent.setData(filterUri);
        startActivity(intent);
    }
}
