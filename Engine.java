

public class Engine implements IEngine {
    private static final String DATABASE_CHECK_URL = "http://shieldantivirus.shieldapps.ml/dbvercheck/check_f1.php";
    private static final String AVIRA_AV_UPDATE_SERVERS_CHECKER = "https://ids.xverify.info/avgeo/";
    private static final String AVIRA_KEY_DOWNLOAD_LOCATION = "/.mobileshield.antivirus/";
    private static final String TAG = Engine.class.getSimpleName();
    private IDatabase database;
    private Date dbBuildDate;
    private File dbFolder;
    private String[] filesToCheck = {"11.adb"};
    private int maxFileSize;
    private String version;
    private String versionvdf;
    private String labelID = "7000";
    private boolean initialized;
    private String updateServer;
    public static boolean errorLicenseKey = false;
    private static LocalScanner localScanner;
    private Engine() {
    }

    private static Engine instance;

    public static synchronized Engine getInstance() {
//        Log.i("MOJ_DEBUG: ", "|Engine| getInstance: " + instance.versionvdf);
        if (instance == null) {
            instance = new Engine();
       //     Log.i("MOJ_DEBUG: ", "|Engine| getInstance. if instance == null- new Engine: " + instance.versionvdf);
        }

        return instance;
    }

    public void setDbFolder(String dbFolder) {
        this.dbFolder = new File(dbFolder);
    }

    @Override
    public boolean initialize(Context context) {

        if (initialized) {
            return true;
        }

        prepareUpdateServer();
        Initializer initializer = MavapiLibController.INSTANCE.initialize(context)
                .add(new MavapiConfig.Builder(context))
                .add(new LocalScannerConfig.Builder(context)
                        .setKeyPath(Environment.getExternalStorageDirectory().getPath() + AVIRA_KEY_DOWNLOAD_LOCATION)
                        .setDetectAdspy(true)
                        .setDetectAdware(true)
                        .setDetectAppl(true)
                        .setDetectPfs(true)
                        .setDetectPua(true)
                        .setDetectSpr(true)
                        .setProductCode("62950"));


        if (updateServer != null) {
            initializer.add(new UpdaterConfig.Builder(context).setUpdateServers(new UpdaterConfig.UpdateServer(updateServer)));
        }

        initializer.build();

        initialized = MavapiLibController.INSTANCE.getLocalScannerController().getInitializationStatus() == InitStatus.SUCCESSFUL;
        Log.i(TAG, "initialized: " + initialized);
        return initialized;

    }

    public void deinitialize() {
        initialized = false;
        instance = null;
    }

    @Override
    public String scan(File file, final ScanCallback callback) {
        Log.e(TAG,"scan. localScanner: " + localScanner);
        if(localScanner == null){
            Log.e(TAG,"scan. localScanner != null");
            LocalScannerController controller = MavapiLibController.INSTANCE.getLocalScannerController();
            localScanner = controller.createInstance();
        }

        localScanner.setScanCallback(new LocalScannerCallback() {
            @Override
            public void onScanComplete(LocalScannerCallbackData localScannerCallbackData) {
                if (localScannerCallbackData.getMalwareInfos().size() > 0) {
                    //malware detected
                    File file = new File(localScannerCallbackData.getFilePath());
                    callback.onScanCompleted(file.getName());
                } else {
                    callback.onScanCompleted(null);
                }
            }

            @Override
            public void onScanError(LocalScannerCallbackData localScannerCallbackData) {

            }
        });
        localScanner.scan(file.getAbsolutePath());
        return null;

    }

    @Override
    public Date getDatabaseBuildDate() {
        return dbBuildDate;
    }

    @Override
    public int getDefinitionsCount() {
        if (database != null) {
            return database.getCount();
        }
        return 0;
    }


    @Override
    public boolean update(IDownloadProgressReceiver progressReceiver) {
        Map<String, UpdaterResult> updaterResultMap = MavapiLibController.INSTANCE.getUpdaterController().updateAllComponents();
        boolean completed = true;

        for (Map.Entry<String, UpdaterResult> entry : updaterResultMap.entrySet()) {
            UpdaterResult updaterResult = entry.getValue();
            
            if(updaterResult == UpdaterResult.ERROR_INVALID_LICENSE){
               errorLicenseKey = true;
            }
            if (updaterResult != UpdaterResult.DONE && updaterResult != UpdaterResult.UP_TO_DATE && updaterResult != UpdaterResult.ERROR_DOWNLOAD) {
                completed = false;
            }
        }
        if (completed && initialized) {

            UpdaterResult _vdfResult = updaterResultMap.get(MavapiLibController.INSTANCE.getLocalScannerController().getUpdateModule().getModuleName());
            if(_vdfResult == UpdaterResult.DONE || _vdfResult == UpdaterResult.UP_TO_DATE){
                String dateString = MavapiLibController.INSTANCE.getLocalScannerController().getVdfSignatureDate();
                //String todayDate= new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                try {
                    dbBuildDate = new SimpleDateFormat("yyyy-MM-dd").parse(dateString);
                    Log.i(TAG, "dbBuildDate: " + dbBuildDate);
                    Log.i(TAG, "dbBuildDate: " + dbBuildDate);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            LocalScannerController newController = MavapiLibController.INSTANCE.getLocalScannerController();
            newController.createInstance();
            version = newController.getEngineVersion();
        }
        if (progressReceiver != null) {
            if (completed) {
                progressReceiver.onCompleted(null);
            } else {
                progressReceiver.onCompleted(new Exception());
            }
        }
        clear();
        return completed;

    }

    @Override
    public boolean isUpdateAvailable() {
        for (String fileName : filesToCheck) {
            try {
                if (post(getRequest(fileName)).charAt(0) == '1')
                    return true;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }
        return false;
    }

    @Override
    public String getVersion() {
        return String.valueOf(version);
    }

    @Override
    public Date getBuildDate() {
        return dbBuildDate;
    }

    @Override
    public void setLabelID(String labelID) {
        this.labelID = labelID;
    }

    @Override
    public String getLabelID() {
        return labelID;
    }


    private String getRequest(String fileName) {
        File dbFile = new File(dbFolder, fileName);
        String queryFormat = "filename=%s&version=%d&platform=ANDROID&labelid=%s&forcelink=%d&flevel=%d&flag=%d&sdkid=%s";
        if (dbFile.exists()) {
            DatabaseHeader header = readHeader(dbFile);
            queryFormat = "filename=%s&version=%d&platform=ANDROID&labelid=%s&flevel=%d&flag=%d&sdkid=%s";
            return String.format(Locale.ENGLISH, queryFormat, fileName, header.getVersion(), labelID, header.getFLevel(), 1, "0");
        }
        return String.format(Locale.ENGLISH, queryFormat, fileName, 0, labelID, 1, 1, 1, "0");
    }

    private String post(String body) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(DATABASE_CHECK_URL);
            byte[] postData = body.getBytes("ASCII");
            conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Content-Length", Integer.toString(postData.length));
            conn.setUseCaches(false);
            conn.getOutputStream().write(postData);

            StringBuilder response = new StringBuilder();
            for (int c; (c = conn.getInputStream().read()) != -1; ) {
                response.append((char) c);
            }
            Log.e(TAG, "post: success");
            return response.toString();
        } catch (Exception e) {
            Log.e(TAG, "post: failure");
            e.printStackTrace();
            return null;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }


    private DatabaseHeader readHeader(File dbFile) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(dbFile);
            return DatabaseHeader.deserialize(in);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null)
                    in.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }



    private String getAviraAvUpdateServerList() {
        HttpURLConnection conn = null;
        try {
            String body = "labelid=" + labelID;
            URL url = new URL(AVIRA_AV_UPDATE_SERVERS_CHECKER);
            byte[] postData = body.getBytes("ASCII");
            conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Content-Length", Integer.toString(postData.length));
            conn.setUseCaches(false);
            conn.getOutputStream().write(postData);

            StringBuilder response = new StringBuilder();
            for (int c; (c = conn.getInputStream().read()) != -1; ) {
                response.append((char) c);
            }
            Log.e(TAG, "getAviraAvUpdateServerList: success");
            return response.toString();
        } catch (Exception e) {
            Log.e(TAG, "getAviraAvUpdateServerList: failure");
            e.printStackTrace();
            return null;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

    private JSONArray parseAviraServers(String response) {
        try {
            JSONObject reader = new JSONObject(response);
            return reader.getJSONArray("updateservers");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void prepareUpdateServer() {
        String response = getAviraAvUpdateServerList();
        if (response != null) {
            JSONArray servers = parseAviraServers(response);
            if (servers != null && servers.length() > 0) {
                try {
                    String server = servers.getString(0);
                    updateServer = "https://" + server;
                    Log.e(TAG, "prepareUpdateServer: updateServer : " + updateServer);
                } catch (JSONException e) {
                    e.printStackTrace();
                    Log.e(TAG, "prepareUpdateServer: error parsing server");
                }
            }
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void clear() {
        MavapiLibController.INSTANCE.getLocalScannerController().clearInstances();
    }
}
