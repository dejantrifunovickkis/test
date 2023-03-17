public class ApplicationsDatabaseHelper extends SQLiteOpenHelper {

    private static final String TAG = ApplicationsDatabaseHelper.class.getSimpleName();
    private static ApplicationsDatabaseHelper INSTANCE;

    private static HashSet<String> whitelistedAppsCam;
    private static HashSet<String> whitelistedAppsMic;

    // Database Version
    private static final int DATABASE_VERSION = 4;

    // Database Name
    private static final String DATABASE_NAME = "applicationDb";

    // Table Names
    private static final String TABLE_APPLICATIONS = "applications";
    private static final String TABLE_PERMISSIONS = "permissions";
    private static final String TABLE_APPS_PERMISSIONS = "apps_permissions";

    // Common column names
    private static final String KEY_ID = "id";
    private static final String KEY_DELETED = "deleted";
    private static final String KEY_CREATED_AT = "created_at";
    private static final String KEY_UPDATED_AT = "updated_at";

    // Applications Table - column names
    private static final String KEY_APP_NAME = "app_name";
    private static final String KEY_APP_PACKAGE_NAME = "app_package";
    private static final String KEY_PATH = "path";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_POINTS = "points";
    private static final String KEY_MARK = "mark";
    private static final String KEY_TYPE = "type";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_WHITELISTED_FOR_CAM = "whitelisted_for_cam";
    private static final String KEY_WHITELISTED_FOR_MIC = "whitelisted_for_mic";

    // Permissions Table - column names
    private static final String KEY_PERMISSION_NAME = "name";
    private static final String KEY_PERMISSION_DESCRIPTION = "description";
    private static final String KEY_PERMISSION_PROTECTION_LEVEL = "level";

    // AppsPermissions Table - column names
    private static final String KEY_APP_ID = "app_id";
    private static final String KEY_PERMISSION_ID = "permission_id";

    private static final String CREATE_TABLE_APPLICATIONS = "CREATE TABLE "
            + TABLE_APPLICATIONS + "("
            + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
            + KEY_APP_NAME + " TEXT,"
            + KEY_APP_PACKAGE_NAME + " TEXT,"
            + KEY_PATH + " TEXT,"
            + KEY_SOURCE + " TEXT,"
            + KEY_POINTS + " INT,"
            + KEY_MARK + " INT,"
            + KEY_TYPE + " INT,"
            + KEY_DESCRIPTION + " TEXT,"
            + KEY_WHITELISTED_FOR_CAM + " INT,"
            + KEY_WHITELISTED_FOR_MIC + " INT,"
            + KEY_DELETED + " INT,"
            + KEY_CREATED_AT + " DATETIME,"
            + KEY_UPDATED_AT + " DATETIME" + ")";

    private static final String CREATE_TABLE_PERMISSIONS = "CREATE TABLE "
            + TABLE_PERMISSIONS + "("
            + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
            + KEY_PERMISSION_NAME + " TEXT,"
            + KEY_PERMISSION_DESCRIPTION + " TEXT,"
            + KEY_PERMISSION_PROTECTION_LEVEL + " INT,"
            + KEY_DELETED + " INT,"
            + KEY_CREATED_AT + " DATETIME,"
            + KEY_UPDATED_AT + " DATETIME" + ")";

    private static final String CREATE_TABLE_APPS_PERMISSIONS = "CREATE TABLE "
            + TABLE_APPS_PERMISSIONS + "("
            + KEY_PERMISSION_ID + " INT,"
            + KEY_APP_ID + " INT,"
            + " PRIMARY KEY(" + KEY_APP_ID + "," + KEY_PERMISSION_ID + ")" + ")";

    @Override
    public void onCreate(SQLiteDatabase sqLiteDatabase) {
        sqLiteDatabase.execSQL(CREATE_TABLE_APPLICATIONS);
        sqLiteDatabase.execSQL(CREATE_TABLE_PERMISSIONS);
        sqLiteDatabase.execSQL(CREATE_TABLE_APPS_PERMISSIONS);
    }

    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int i, int i1) {
        sqLiteDatabase.execSQL("DROP TABLE IF EXISTS " + TABLE_APPLICATIONS);
        sqLiteDatabase.execSQL("DROP TABLE IF EXISTS " + TABLE_PERMISSIONS);
        sqLiteDatabase.execSQL("DROP TABLE IF EXISTS " + TABLE_APPS_PERMISSIONS);
        onCreate(sqLiteDatabase);
    }

    private ApplicationsDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    public static synchronized ApplicationsDatabaseHelper getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ApplicationsDatabaseHelper(PSApplication.getContext());
        }
        return INSTANCE;
    }

    public List<ApplicationModel> getApps() {
        List<ApplicationModel> list = new ArrayList<>();
        SQLiteDatabase db = this.getWritableDatabase();

        Cursor appsCursor = db.query(TABLE_APPLICATIONS, null, null, null, null, null, null);
        while (appsCursor.moveToNext()) {
            ApplicationModel model = getAppModelFromCursor(appsCursor);
            list.add(model);
        }
        appsCursor.close();

        return list;
    }

    public List<SystemPermission> getAllPermissions() {
        List<SystemPermission> list = new ArrayList<>();
        SQLiteDatabase db = this.getWritableDatabase();

        Cursor permissionCursor = db.query(TABLE_PERMISSIONS, null, null, null, null, null, null);
        while (permissionCursor.moveToNext()) {
            SystemPermission systemPermission = getSystemPermissionFromCursor(permissionCursor);
            list.add(systemPermission);
        }
        permissionCursor.close();
        return list;
    }


    public long insertApp(ApplicationModel app) {
        SQLiteDatabase db = this.getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put(KEY_APP_NAME, app.getName());
        values.put(KEY_APP_PACKAGE_NAME, app.getPackageName());
        values.put(KEY_PATH, app.getPath());
        values.put(KEY_SOURCE, app.getSource());
        values.put(KEY_POINTS, app.getPoints());
        values.put(KEY_MARK, app.getMark());
        values.put(KEY_DESCRIPTION, app.getDescription());
        values.put(KEY_DELETED, 0);
        values.put(KEY_CREATED_AT, getDateTime());
        values.put(KEY_UPDATED_AT, getDateTime());

        if (whitelistedAppsCam.contains(app.getPackageName())) {
            values.put(KEY_WHITELISTED_FOR_CAM, 1);
        }

        if (whitelistedAppsMic.contains(app.getPackageName())) {
            values.put(KEY_WHITELISTED_FOR_MIC, 1);
        }

        long app_id = db.insert(TABLE_APPLICATIONS, null, values);


        for (SystemPermission permission : app.getPermissionsList()) {
            long permission_id;
            Cursor c = db.query(TABLE_PERMISSIONS, null, KEY_PERMISSION_NAME + "= ? ", new String[]{permission.getName()}, null, null, null);
            if (!c.moveToFirst()) {
                ContentValues cv = getValuesForPermission(permission);
                permission_id = db.insert(TABLE_PERMISSIONS, null, cv);
            } else {
                permission_id = c.getLong(c.getColumnIndex(KEY_ID));
            }
            c.close();
            db.insert(TABLE_APPS_PERMISSIONS, null, getAppPermissionValue(app_id, permission_id));
        }

        return app_id;
    }

    private ContentValues getValuesForPermission(SystemPermission permission) {
        ContentValues values = new ContentValues();
        values.put(KEY_PERMISSION_NAME, permission.getName());
        values.put(KEY_PERMISSION_DESCRIPTION, permission.getDescription());
        values.put(KEY_PERMISSION_PROTECTION_LEVEL, permission.getLevel());
        values.put(KEY_DELETED, 0);
        values.put(KEY_CREATED_AT, getDateTime());
        values.put(KEY_UPDATED_AT, getDateTime());

        return values;
    }

    private ContentValues getAppPermissionValue(long appId, long permissionId) {
        ContentValues values = new ContentValues();
        values.put(KEY_PERMISSION_ID, permissionId);
        values.put(KEY_APP_ID, appId);
        return values;
    }

    private String getDateTime() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date date = new Date();
        return dateFormat.format(date);
    }

    private ApplicationModel getAppModelFromCursor(Cursor c) {

        long appId = c.getLong(c.getColumnIndex(KEY_ID));
        ApplicationModel model = new ApplicationModel();
        model.setName(c.getString(c.getColumnIndex(KEY_APP_NAME)));
        model.setPackageName(c.getString(c.getColumnIndex(KEY_APP_PACKAGE_NAME)));
        model.setPath(c.getString(c.getColumnIndex(KEY_PATH)));
        model.setSource(c.getString(c.getColumnIndex(KEY_SOURCE)));
        model.setDescription(c.getString(c.getColumnIndex(KEY_DESCRIPTION)));
        model.setPoints(c.getInt(c.getColumnIndex(KEY_POINTS)));
        model.setMark(c.getInt(c.getColumnIndex(KEY_MARK)));
        model.setType(c.getInt(c.getColumnIndex(KEY_TYPE)));
        model.setWhitelistedForCam(c.getInt(c.getColumnIndex(KEY_WHITELISTED_FOR_CAM)) == 1);
        model.setWhitelistedForMic(c.getInt(c.getColumnIndex(KEY_WHITELISTED_FOR_MIC)) == 1);

        try {
            PackageManager pm = PSApplication.getContext().getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(model.getPackageName(), 0);
            model.setIcon(pm.getApplicationIcon(info));
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        SQLiteDatabase db = this.getWritableDatabase();
        String query = "SELECT permissions.name AS name, permissions.description AS description, permissions.level AS level" +
                " FROM apps_permissions JOIN permissions ON apps_permissions.permission_id = permissions.id WHERE apps_permissions.app_id = " + appId;
        Cursor permissionsCursor = db.rawQuery(query, null);
        List<SystemPermission> permissions = new ArrayList<>();
        while (permissionsCursor.moveToNext()) {
            SystemPermission permission = new SystemPermission();
            permission.setName(permissionsCursor.getString(permissionsCursor.getColumnIndex(KEY_PERMISSION_NAME)));
            permission.setDescription(permissionsCursor.getString(permissionsCursor.getColumnIndex(KEY_PERMISSION_DESCRIPTION)));
            permission.setLevel(permissionsCursor.getInt(permissionsCursor.getColumnIndex(KEY_PERMISSION_PROTECTION_LEVEL)));
            permissions.add(permission);
        }
        model.setPermissionsList(permissions);
        permissionsCursor.close();
        return model;
    }

    private SystemPermission getSystemPermissionFromCursor(Cursor c) {

        long permissionId = c.getLong(c.getColumnIndex(KEY_ID));
        SystemPermission permission = new SystemPermission();
        permission.setName(c.getString(c.getColumnIndex(KEY_PERMISSION_NAME)));
        permission.setDescription(c.getString(c.getColumnIndex(KEY_PERMISSION_DESCRIPTION)));
        permission.setLevel(c.getInt(c.getColumnIndex(KEY_PERMISSION_PROTECTION_LEVEL)));

        SQLiteDatabase db = this.getWritableDatabase();
        String query = "SELECT * FROM apps_permissions JOIN applications ON apps_permissions.app_id = applications.id WHERE apps_permissions.permission_id = " + permissionId;
        Cursor modelsCursor = db.rawQuery(query, null);
        DatabaseUtils.dumpCursor(modelsCursor);
        List<ApplicationModel> applicationModels = new ArrayList<>();
        while (modelsCursor.moveToNext()) {
            ApplicationModel model = new ApplicationModel();
            model.setName(modelsCursor.getString(modelsCursor.getColumnIndex(KEY_APP_NAME)));
            model.setPackageName(modelsCursor.getString(modelsCursor.getColumnIndex(KEY_APP_PACKAGE_NAME)));
            model.setPath(modelsCursor.getString(modelsCursor.getColumnIndex(KEY_PATH)));
            model.setSource(modelsCursor.getString(modelsCursor.getColumnIndex(KEY_SOURCE)));
            model.setDescription(modelsCursor.getString(modelsCursor.getColumnIndex(KEY_DESCRIPTION)));
            model.setPoints(modelsCursor.getInt(modelsCursor.getColumnIndex(KEY_POINTS)));
            model.setMark(modelsCursor.getInt(modelsCursor.getColumnIndex(KEY_MARK)));
            model.setType(modelsCursor.getInt(modelsCursor.getColumnIndex(KEY_TYPE)));
            model.setWhitelistedForCam(modelsCursor.getInt(modelsCursor.getColumnIndex(KEY_WHITELISTED_FOR_CAM)) == 1);
            model.setWhitelistedForMic(modelsCursor.getInt(modelsCursor.getColumnIndex(KEY_WHITELISTED_FOR_MIC)) == 1);
            try {
                PackageManager pm = PSApplication.getContext().getPackageManager();
                ApplicationInfo info = pm.getApplicationInfo(model.getPackageName(), 0);
                model.setIcon(pm.getApplicationIcon(info));
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
            applicationModels.add(model);
        }
        permission.setApplicationModels(applicationModels);
        modelsCursor.close();
        return permission;
    }

    public ApplicationModel getApp(String packageName) {

        SQLiteDatabase db = this.getWritableDatabase();
        Cursor modelCursor = db.query(TABLE_APPLICATIONS, null, KEY_APP_PACKAGE_NAME + "= ? ", new String[]{packageName}, null, null, null);
        ApplicationModel model = null;
        while (modelCursor.moveToNext()) {
            model = getAppModelFromCursor(modelCursor);
        }
        modelCursor.close();
        return model;
    }

    public void deleteAllApps() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_APPLICATIONS, null, null);
        db.delete(TABLE_PERMISSIONS, null, null);
        db.delete(TABLE_APPS_PERMISSIONS, null, null);
    }

    public void setWhitelistedForCam(String appPackage, boolean whitelisted) {

        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(KEY_WHITELISTED_FOR_CAM, whitelisted);
        int rows = db.update(TABLE_APPLICATIONS, cv, KEY_APP_PACKAGE_NAME + "= ? ", new String[]{appPackage});
        return;
    }

    public void setWhitelistedForMic(String appPackage, boolean whitelisted) {

        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(KEY_WHITELISTED_FOR_MIC, whitelisted);
        int rows = db.update(TABLE_APPLICATIONS, cv, KEY_APP_PACKAGE_NAME + "= ? ", new String[]{appPackage});
        return;
    }

    public void logDb() {
        SQLiteDatabase db = this.getWritableDatabase();
        Cursor c = db.query(TABLE_APPLICATIONS, null, null, null, null, null, null);
        Cursor p = db.query(TABLE_PERMISSIONS, null, null, null, null, null, null);
        Cursor ap = db.query(TABLE_APPS_PERMISSIONS, null, null, null, null, null, null);
        DatabaseUtils.dumpCursor(p);
        c.close();
        p.close();
        ap.close();
    }

    public void prepareWhitelistForCam() {
        whitelistedAppsCam = new HashSet<>();
        SQLiteDatabase db = this.getWritableDatabase();
        Cursor c = db.query(TABLE_APPLICATIONS, null, null, null, null, null, null);
        while (c.moveToNext()) {
            if (c.getInt(c.getColumnIndex(KEY_WHITELISTED_FOR_CAM)) == 1) {
                whitelistedAppsCam.add(c.getString(c.getColumnIndex(KEY_APP_PACKAGE_NAME)));
            }
        }
        c.close();
    }

    public void prepareWhitelistForMic() {
        whitelistedAppsMic = new HashSet<>();
        SQLiteDatabase db = this.getWritableDatabase();
        Cursor c = db.query(TABLE_APPLICATIONS, null, null, null, null, null, null);
        while (c.moveToNext()) {
            if (c.getInt(c.getColumnIndex(KEY_WHITELISTED_FOR_MIC)) == 1) {
                whitelistedAppsMic.add(c.getString(c.getColumnIndex(KEY_APP_PACKAGE_NAME)));
            }
        }
        c.close();
    }
}
