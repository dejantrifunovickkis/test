
public class ScanningService extends Service {

    private static final String TAG = ScanningService.class.getSimpleName();

    public static final String SERVICE_ACTION = "service_action";
    public static final int ACTION_NONE = 0;
    public static final int ACTION_INTERRUPT = 1;


    public static final String SCAN_TYPE = "scan_type";
    public static final String SCAN_DATA = "scan_data";
    public static final String SCAN_DATA_TYPE = "scan_data_type";

    public static final String NEW_APPLICATION = "app";
    public static final String NEW_FILE = "file";

    public static final int SCHEDULED_SCAN = 0;
    public static final int ON_DEMAND_SCAN = 1;
    public static final int REAL_TIME_SCAN = 2;
    public static final int CUSTOM_SCAN = 3;

    private static final int SCAN_START = 0;
    private static final int SCAN_PROGRESS = 1;
    private static final int SCAN_FINISHED = 2;
    private static final int SCAN_INTERRUPTED = 3;

    private static final int SCAN_INIT_START = 0;
    private static final int SCAN_INIT_PROGRESS = 1;
    private static final int SCAN_INIT_FINISHED = 2;

    private static final int INTERRUPT_INTERVAL = 60000;
    public static final String ACTION_START_SCANNING = "startScanning";


    private int scanType;
    private String scanData;
    private String scanDataType;
    private FileScanWrapper realtimeFileScanWrapper;

    public static boolean notificationCalled;
    public static boolean serviceRunning;
    public static boolean scanRunning;
    public static boolean scanInterrupted;
    public static boolean scanFinished;
    private volatile List<ThreatModel> threats = new CopyOnWriteArrayList<>();
    private volatile int count;
    private volatile AtomicInteger fileCount = new AtomicInteger();
    private volatile AtomicInteger scanFinishedCalled;
    private ExecutorService executorService;

    private volatile long start;
    private volatile long end;
    private CountDownLatch doneSignal;
    private Engine engine;
    private long progressNum;
    private String rootFile;
    //    private boolean appBehavior;
    private PackageManager pm;
    private Handler handler;
    private Intent mIntent;
    private File latest;
    public static boolean isInterrupted;
    private Thread scanningThread;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "ScanningService creating");
        setServiceVar(false);
        scanRunning = false;
        scanInterrupted = false;
        scanFinished = false;
        scanFinishedCalled = new AtomicInteger();
        handler = new Handler();
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {


        mIntent = intent;
        Log.i(TAG, "Received start id " + startId + ": " + intent + ", scan type: " + intent.getIntExtra(SCAN_TYPE, -1) + ", scan data: " + intent.getStringExtra(SCAN_DATA));
        int action = intent.getIntExtra(SERVICE_ACTION, ACTION_NONE);
//        NOTE: Zakomentarisan deo radi release-a, kod je za widget i treba ga otkomentarisati da bi widget ponovo radio
//        String actionString = intent.getAction();
//        Log.i(TAG, "actionString from widget " + actionString);
//        if (actionString != null && actionString.equals(ACTION_START_SCANNING)) {
//            Log.i(TAG, "Starting scan from widget");
//            scanType = intent.getIntExtra(SCAN_TYPE, SCHEDULED_SCAN);
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//                if (!Environment.isExternalStorageManager()) {
//                    Log.i(TAG, "Permission all access files is not granted");
//                } else {
//                    startScannerFromWidget(intent);
//                }
//            } else {
//                startScannerFromWidget(intent);
//            }
//
//        } else {
        if (action == ACTION_NONE) {
            startScanner(intent);
        } else {
            setScanInterrupted(true);
        }
//        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {

        if(scanningThread != null){
            scanningThread.isInterrupted();
        }
        if(scanRunning){
            serviceShutdown();
        }
        setServiceVar(false);
        Log.i(TAG, "ScanningService destroying");
        super.onDestroy();
    }

    public void startScanner(Intent intent) {
        Log.i(TAG, "startScanner: " + intent.getStringExtra(SCAN_DATA));
        if (!serviceRunning || !scanRunning || scanFinished) {
            setServiceVar(true);
            scanType = intent.getIntExtra(SCAN_TYPE, ON_DEMAND_SCAN);
            Log.e(TAG, "startScanner: " + scanType);
            scanData = intent.getStringExtra(SCAN_DATA);
            scanDataType = intent.getStringExtra(SCAN_DATA_TYPE);
            BackStack.isAppInRecentTab = false;
            scanFiles();
        } else {
            Log.e(TAG, "startScanner: already running");
        }
    }

    public void startScannerFromWidget(Intent intent) {
        Log.i(TAG, "startScanner from widget: ");
        MyAppWidgetProvider.currentState = MyAppWidgetProvider.SCANNING;
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);
        int[] appWidgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(this, MyAppWidgetProvider.class));
        MyAppWidgetProvider.updateAppWidget(this, appWidgetManager, appWidgetIds[0]);
        if (!serviceRunning || !scanRunning || scanFinished) {
            setServiceVar(true);
            scanType = intent.getIntExtra(SCAN_TYPE, SCHEDULED_SCAN);
            Log.e(TAG, "startScanner: " + scanType);
            scanData = intent.getStringExtra(SCAN_DATA);
            scanDataType = intent.getStringExtra(SCAN_DATA_TYPE);
            BackStack.isAppInRecentTab = false;
            scanFiles();
        } else {
            Log.e(TAG, "startScanner: already running");
        }
    }

    public boolean isInterrupted() {
        return scanInterrupted;
    }

    private void setScanInterrupted(boolean isInterrupted) {
        Log.i(TAG, "setScanInterrupted: " + isInterrupted);
        scanInterrupted = isInterrupted;
        serviceShutdown();
    }

    private void scanFiles() {
        scheduleStopSelfIfScanNotStarted();
        scanningThread = new Thread(new Runnable() {
            @Override
            public void run() {
                // skeniranje fajlova ovde
                scanRunning = true;
                scanFinished = false;
                engine = Engine.getInstance();
                if (!engine.isInitialized()) {
                    engine.initialize(getContext());
                    Log.e(TAG, "scan files: " + engine.getDefinitionsCount());
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        finish();
                    }
                }
                publishProgress(SCAN_START, 0, 0, null);

                start = SystemClock.elapsedRealtime();
                Log.e(TAG, "time_measure: start time: " + start);

                int cores = Utilities.getNumberOfCores();
                executorService = Executors.newFixedThreadPool(cores, new CustomThreadFactory());

                List<FileScanWrapper> roots = new ArrayList<>();

                if (scanType == REAL_TIME_SCAN && scanData != null) {
                    if (scanDataType.equals(NEW_APPLICATION)) {

                        try {
                            ApplicationInfo applicationInfo = pm.getApplicationInfo(scanData, PackageManager.GET_META_DATA);
                            String scanPath = applicationInfo.sourceDir;
                            realtimeFileScanWrapper = new FileScanWrapper(new File(scanPath), true);
                        } catch (PackageManager.NameNotFoundException e) {
                            Log.e(TAG, "scan: Name not found");
                        }

                    } else {
                        findLatest(Environment.getExternalStorageDirectory());
                        if (latest != null) {
                            realtimeFileScanWrapper = new FileScanWrapper(latest, false);
                            Log.e(TAG, "run: latest -" + latest.getAbsolutePath());
                        } else {
                            Log.e(TAG, "run: latest - null");
                        }
                    }
                    roots.add(realtimeFileScanWrapper);

                } else {

                    boolean scanAll = false;
                    if (scanType == SCHEDULED_SCAN || scanType == ON_DEMAND_SCAN) {
                        scanAll = true;
                    }
                    roots = FileModel.getFilesSelectedForScan(getContext(), scanAll);
                    Collections.shuffle(roots);
                }
                count = roots.size();
                if (!(scanType == REAL_TIME_SCAN)) {
                    int fileSize = FileModel.filesCountForScan;
                    publishInitProgress(SCAN_INIT_PROGRESS, 0);
                    publishInitProgress(SCAN_INIT_FINISHED, fileSize);
                }
                doneSignal = new CountDownLatch(count);
                if (scanInterrupted) {
                    serviceShutdown();
                }
                for (FileScanWrapper root : roots) {
                    ScanningRunnable task = new ScanningRunnable(root);
                    if (!executorService.isShutdown()) {
                        executorService.submit(task);
                    }
                }
                if (scanInterrupted) {
                    Log.e(TAG, "scan iterrupted scanFiles() ");
                    serviceShutdown();
                }

                if (roots.size() == 0) {
                    Log.e(TAG, "end finish, roots size = 0");
                    finish();
                }

            }
        });
        scanningThread.start();
    }

    private void stopScanning() {
        if (scanningThread != null) {
            scanningThread.interrupt();
            scanningThread = null;
        }
    }

    private void scanFilesb() {
        scheduleStopSelfIfScanNotStarted();
//        AvLogger.e(getApplicationContext(),"MOJ_DEBUG: ","|ScanningService| .scanFiles");
        Utilities.scanLooperThread.postRunnable(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "run: scan started");
                scanRunning = true;
                scanFinished = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        finish();
                    }
                }
                SharedPreferences prefs = getSharedPreferences(Constants.APP_STATE.STATE_SHARED_PREFERENCES, Context.MODE_PRIVATE);
                pm = getContext().getPackageManager();

                publishProgress(SCAN_START, 0, 0, null);

                start = SystemClock.elapsedRealtime();
                Log.e(TAG, "time_measure: start time: " + start);

                int cores = Utilities.getNumberOfCores();
                executorService = Executors.newFixedThreadPool(cores, new CustomThreadFactory());
                engine = Engine.getInstance();
                if (!engine.isInitialized()) {
                    engine.initialize(getContext());
                    Log.e(TAG, "scan files: " + engine.getDefinitionsCount());
                }

                List<FileScanWrapper> roots = new ArrayList<>();

                if (scanType == REAL_TIME_SCAN && scanData != null) {
                    if (scanDataType.equals(NEW_APPLICATION)) {

                        try {
                            ApplicationInfo applicationInfo = pm.getApplicationInfo(scanData, PackageManager.GET_META_DATA);
                            String scanPath = applicationInfo.sourceDir;
                            realtimeFileScanWrapper = new FileScanWrapper(new File(scanPath), true);
                        } catch (PackageManager.NameNotFoundException e) {
                            Log.e(TAG, "scan: Name not found");
                        }

                    } else {
                        findLatest(Environment.getExternalStorageDirectory());
                        if (latest != null) {
                            realtimeFileScanWrapper = new FileScanWrapper(latest, false);
                            Log.e(TAG, "run: latest -" + latest.getAbsolutePath());
                        } else {
                            Log.e(TAG, "run: latest - null");
                        }
                    }
                    roots.add(realtimeFileScanWrapper);

                } else {

                    boolean scanAll = false;
                    if (scanType == SCHEDULED_SCAN || scanType == ON_DEMAND_SCAN) {
                        scanAll = true;
                    }
                    roots = FileModel.getFilesSelectedForScan(getContext(), scanAll);
                    Collections.shuffle(roots);
                }
                count = roots.size();
                if (!(scanType == REAL_TIME_SCAN)) {
                    int fileSize = FileModel.filesCountForScan;
                    publishInitProgress(SCAN_INIT_PROGRESS, 0);
                    publishInitProgress(SCAN_INIT_FINISHED, fileSize);
                }
                doneSignal = new CountDownLatch(count);
                if (scanInterrupted) {
                    serviceShutdown();
                }
                for (FileScanWrapper root : roots) {
                    ScanningRunnable task = new ScanningRunnable(root);
                    if (!executorService.isShutdown()) {
                        executorService.submit(task);
                    }
                }
                if (roots.size() == 0) {
                    Log.e(TAG, "end finish, roots size = 0");
                    finish();
                }

            }
        }, 500);
    }

    private void scheduleStopSelfIfScanNotStarted() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Log.e(TAG, "run: scheduled stopping in 1000ms");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                Log.e(TAG, "run: scheduled stopping checking");
                if (!scanRunning) {
                    Log.e(TAG, "run: scheduled stopping : interrupting");
                    stopSelf();
                } else {
                    Log.e(TAG, "run: scheduled stopping : not stopping, scanRunning");
                }
            }
        }).start();
    }

    private void dispatch(FileScanWrapper fileWrapper) {
        if (isInterrupted()) {
            Log.e(TAG, "scan iterrupted dispatch " + isInterrupted());
            return;
        }
        //AvLogger.e(getApplicationContext(),"MOJ_DEBUG: ","|ScanningService| .dispatch fileWrapper: ");
        if (fileWrapper.getFile().isDirectory()) {
            scanDirectory(fileWrapper, null);
        } else if (Zip.isZipFile(fileWrapper.getFile())) {
            scanZip(fileWrapper, null);
        } else {
            scanSingleFile(fileWrapper, null);
        }

    }

    //    private void scanZip(FileScanWrapper fileWrapper, ThreatModel zipThreat) {
//        //AvLogger.e(getApplicationContext(),"MOJ_DEBUG: ","|ScanningService| .scanZip: ");
//        if (isInterrupted()) {
//            return;
//        }
//        ThreatModel zipThreatNew;
//        if (zipThreat == null) {
//            if (fileWrapper.isInstalledApp()) {
//                PackageInfo packageInfo = pm.getPackageArchiveInfo(fileWrapper.getFile().getPath(), PackageManager.GET_META_DATA);
//                zipThreatNew = ThreatModel.createInsideInstalledAppThreatModel(null, null, packageInfo.packageName);
//            } else {
//                zipThreatNew = ThreatModel.createInsideArchiveThreatModel(null, null, fileWrapper.getFile().getAbsolutePath());
//            }
//        } else {
//            zipThreatNew = zipThreat;
//        }
//        scanSingleFile(fileWrapper, zipThreatNew);
//    }

    private void scanZip(FileScanWrapper fileWrapper, ThreatModel zipThreat) {
        if (isInterrupted()) {
            return;
        }
        ThreatModel zipThreatNew;
        if (zipThreat == null) {
            if (fileWrapper.isInstalledApp()) {
                if (pm == null) {
                    pm = getPackageManager();
                }
                PackageInfo packageInfo = pm.getPackageArchiveInfo(fileWrapper.getFile().getPath(), PackageManager.GET_META_DATA);
                zipThreatNew = ThreatModel.createInsideInstalledAppThreatModel(null, null, packageInfo.packageName);
            } else {
                zipThreatNew = ThreatModel.createInsideArchiveThreatModel(null, null, fileWrapper.getFile().getAbsolutePath());
            }
        } else {
            zipThreatNew = zipThreat;
        }
        scanSingleFile(fileWrapper, zipThreatNew);
    }

    private void scanDirectory(FileScanWrapper root, ThreatModel zipThreat) {
        //AvLogger.e(getApplicationContext(),"MOJ_DEBUG: ","|ScanningService| .scanDirectory ");
        if (isInterrupted()) {
            return;
        }
        File[] listOfFiles = root.getFile().listFiles();
        for (File f : listOfFiles) {
            if (f.isDirectory()) {
                scanDirectory(new FileScanWrapper(f, false), zipThreat);
            } else {
                scanSingleFile(new FileScanWrapper(f, false), zipThreat);
            }
        }
    }

    private void scanSingleFile(FileScanWrapper f, ThreatModel zipThreat) {
        // AvLogger.e(getApplicationContext(),"MOJ_DEBUG: ","|ScanningService| .scanSingleFile: ");
        if (isInterrupted()) {
            return;
        }

        fileCount.incrementAndGet();
        publishProgress(SCAN_PROGRESS, threats.size(), progressNum, rootFile);
        engine.scan(f.getFile(), virusName -> {
            if (virusName != null) {
                if (zipThreat != null) {
                    zipThreat.setPath(f.getFile().getAbsolutePath());
                    zipThreat.setDescription(virusName);
                    addThreat(zipThreat);
                } else {
                    String name = f.getFile().getAbsolutePath();

                    if (f.getFile().getAbsolutePath().contains(getFilesDir().getAbsolutePath())) {
                        name = name.substring(AVApplication.getUnzipDirectory().length());
                    }
                    ThreatModel model = ThreatModel.createSingleFileThreatModel(null, name);
                    model.setCause(virusName);
                    addThreat(model);
                }
            }
        });
    }

    private long saveReport(List<ThreatModel> threats, long elapsedTime, String scanRoot, int scanType, int fileCount) {
        Log.e(TAG, "saveReport: ");
        ReportsDatabaseHelper db = ReportsDatabaseHelper.getInstance();

        ReportModel report = new ReportModel();
        report.setThreatsFound(threats.size());
        report.setScanType(scanType);
        report.setScanPath(scanRoot);
        report.setFileCount(fileCount);
        report.setScanDuration(elapsedTime);
        Long reportId = db.insertReport(report);

        if (threats.size() > 0) {
            db.insertThreats(threats, reportId);
        }
        return reportId;
    }

    private void publishProgress(int event, final int issues, final float progress, final String currentFile) {

        if (event == SCAN_START) {
            Utilities.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    BusProvider.post(new ScanStartedEvent());
                }
            });
        } else if (event == SCAN_PROGRESS) {
            Log.i(TAG, "scan in progress");
//            MyAppWidgetProvider.currentState = MyAppWidgetProvider.PROGRESS;
//            MyAppWidgetProvider.threatsFound = threats.size();
//            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);
//            int[] appWidgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(this, MyAppWidgetProvider.class));
//            MyAppWidgetProvider.updateAppWidget(this, appWidgetManager, appWidgetIds[0]);

            interruptServiceIfIdle(true);
            Utilities.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    BusProvider.post(new ScanProgressEvent(issues, progress, currentFile + " " + doneSignal.getCount(), fileCount.get()));
                }
            });
        } else if (event == SCAN_FINISHED) {
            interruptServiceIfIdle(false);
            Utilities.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    BusProvider.post(new ScanFinishedEvent(issues, progress, currentFile, fileCount.get()));
                    ScanReportPresenter.isScanReportShown = false;
                }
            });
        } else if (event == SCAN_INTERRUPTED) {
            Log.i(TAG, "|ScanningService| SCAN_INTERRUPTED");
            interruptServiceIfIdle(false);
            stopScanning();
            Utilities.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    BusProvider.post(new ScanInterruptedEvent(issues, progress, currentFile, fileCount.get()));
                }
            });
        }
    }

    //////// NEW CODE FOR INITIALIZATON PROGRESS BAR SCAN //////////////////
    private void publishInitProgress(int event, int fileSize) {

        if (event == SCAN_INIT_START) {
            Utilities.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    BusProvider.post(new ScanInitStartedEvent(fileSize));
                }
            });
        } else if (event == SCAN_INIT_PROGRESS) {
            interruptServiceIfIdle(true);
            Utilities.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    BusProvider.post(new ScanInitProgressEvent(fileSize));
                }
            });
        } else if (event == SCAN_INIT_FINISHED) {
            interruptServiceIfIdle(false);
            Utilities.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    BusProvider.post(new ScanInitFinishedEvent(fileSize));
                }
            });
        }
    }

    private synchronized void finish() {
        scanFinished = true;
        if (scanFinishedCalled.get() > 0) {
            return;
        }
        scanFinishedCalled.incrementAndGet();
        Log.e(TAG, "finish: ");
        boolean deleted = FileUtils.deleteDirectory(new File(AVApplication.getUnzipDirectory()));
        Log.e(TAG, "finish: deleted " + deleted);
        end = SystemClock.elapsedRealtime();
        Log.e(TAG, "time_measure: end time: " + end);
        long elapsedTime = end - start;
        Log.e(TAG, "time_measure: elapsed: " + elapsedTime);
//              Insert report to the database
        long reportId = -1;
        if (scanType == REAL_TIME_SCAN && threats.size() == 0) {
            Log.i(TAG, "finish: real time scan, not saving (0 threats).");
        } else {
            reportId = saveReport(threats, elapsedTime, ReportModel.SCAN_ROOT_ALL, scanType, fileCount.get());
        }

        if (scanInterrupted) {
            publishProgress(SCAN_INTERRUPTED, threats.size(), 0, null);
            Log.i(TAG, "finish: scan interrupted");
        } else {
            publishProgress(SCAN_FINISHED, threats.size(), 0, null);
            Log.i(TAG, "finish: scan finished");
            if (BackStack.isAppInRecentTab) {
                notificationForFinishedScan();
            }

        }
        if (scanType == REAL_TIME_SCAN && threats.size() > 0) {
            notifyIfRealTime(reportId);
        } else if (scanType == SCHEDULED_SCAN) {
            notifyIfScheduled();
//            MyAppWidgetProvider.currentState = MyAppWidgetProvider.FINISHED;
//            MyAppWidgetProvider.threatsFound = threats.size();
//            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);
//            int[] appWidgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(this, MyAppWidgetProvider.class));
//            MyAppWidgetProvider.updateAppWidget(this, appWidgetManager, appWidgetIds[0]);
        }
       cleanEngine();
        MainActivity.shortcutScan = false;
        FileContentObserver.called = false;
        AlarmReceiver.completeWakefulIntent(mIntent);
        stopSelf();
    }
    private void cleanEngine() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                engine.clear();
            }
        }).start();
    }
    private class ScanningRunnable implements Runnable {

        private final FileScanWrapper root;

        public ScanningRunnable(FileScanWrapper root) {
            this.root = root;
        }

        @Override
        public void run() {
            // Send file for scanning
            if (isInterrupted()) {
                return;
            }
            try {
                dispatch(root);
            } catch (Exception e) {
                Log.e(TAG, "run: error scanning: ", e);
            } finally {
                doneSignal.countDown();
                progressNum = 100 - (doneSignal.getCount() * 100) / count;
                rootFile = root.getFile().getAbsolutePath();
                if (doneSignal.getCount() == 0 || isInterrupted()) {
                    Log.e(TAG, "finish: threats - " + threats.size() + " " + doneSignal.getCount() + "/" + count + " : " + progressNum + " " + rootFile);
                    finish();
                }
            }
        }
    }

    private void addThreat(ThreatModel model) {
        if (!ReportsDatabaseHelper.getInstance().isIgnored(model)) {
            threats.add(model);
        }
    }

    private void notifyIfRealTime(long reportId) {
        if (threats != null && threats.size() > 0) {
            Intent intent2 = new Intent(getContext(), AlertDialogActivity.class);
            intent2.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            intent2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent2.putExtra(AlertDialogActivity.REPORT_ID, reportId);
            intent2.putExtra(AlertDialogActivity.THREAT_NAME, threats.get(0).getName());
            intent2.putExtra(AlertDialogActivity.THREAT_DESCRIPTION, threats.get(0).getDescription());
            intent2.putExtra(AlertDialogActivity.THREAT_TYPE, scanDataType);
            if (scanDataType.equals(NEW_APPLICATION)) {
                Log.i(TAG, "ScanningService .notifyIfRealTime scanDataType = New App ");
                intent2.putExtra(AlertDialogActivity.THREAT_DATA, scanData);
            } else {
                if (realtimeFileScanWrapper != null && realtimeFileScanWrapper.getFile() != null && realtimeFileScanWrapper.getFile().getAbsolutePath() != null) {
                    Log.i(TAG, "ScanningService .notifyIfRealTime not null ");
                    intent2.putExtra(AlertDialogActivity.THREAT_DATA, realtimeFileScanWrapper.getFile().getAbsolutePath());
                } else {
                    Log.i(TAG, "ScanningService .notifyIfRealTime else");
                    intent2.putExtra(AlertDialogActivity.THREAT_DATA, getString(R.string.na));
                }
            }
            Log.i(TAG, "ScanningService .notifyIfRealTime intent open");
            getContext().startActivity(intent2);
        }
    }

    private void notifyIfScheduled() {
        String message;
        if (threats.size() == 0) {
            message = getString(R.string.scheduled_scan_results) + getString(R.string.no_malware_detected);
        } else if (threats.size() == 1) {
            message = getString(R.string.scheduled_scan_results) + threats.size() + getString(R.string.threat_found);
        } else {
            message = getString(R.string.scheduled_scan_results) + threats.size() + getString(R.string.threats_found);
        }
        Intent nextIntent = new Intent(this, MainActivity.class);
        nextIntent.putExtra(Constants.APP_STATE.DEEP_LINK, MenuProvider.SCAN_REPORT_TAG);
        nextIntent.setAction("dummyAction");
        PendingIntent pNextIntent = PendingIntent.getActivity(this, 0,
                nextIntent, PendingIntent.FLAG_IMMUTABLE);
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            CharSequence name = getString(R.string.app_name);
            NotificationChannel mChannel = new NotificationChannel(Constants.NOTIFICATION_ID.SCHEDULED_SCAN_RESULT + "", name, NotificationManager.IMPORTANCE_LOW);
            mChannel.setSound(null, null);
            mChannel.enableLights(false);
            mChannel.enableVibration(false);
            mNotificationManager.createNotificationChannel(mChannel);
        }
        Notification notification = new NotificationCompat.Builder(this, Constants.NOTIFICATION_ID.SCHEDULED_SCAN_RESULT + "")
                .setContentTitle(getString(R.string.app_name_short))
                .setTicker(getString(R.string.app_name_short))
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pNextIntent)
                .setAutoCancel(true)
                .build();

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        notificationManager.notify(0, notification);
    }

    public void notificationForFinishedScan() {
        Log.i(TAG, "|ScanningService| notificationForFinishedScan. called from notify");
        notificationCalled = true;
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            CharSequence name = getString(R.string.app_name);
            NotificationChannel mChannel = new NotificationChannel(Constants.NOTIFICATION_ID.SCHEDULED_SCAN_RESULT + "", name, NotificationManager.IMPORTANCE_LOW);
            mChannel.setSound(null, null);
            mChannel.enableLights(false);
            mChannel.enableVibration(false);
            mNotificationManager.createNotificationChannel(mChannel);
        }
        String message;
        if (threats.size() == 0) {
            message = getString(R.string.scan_report) + ": " + getString(R.string.no_malware_detected);
        } else if (threats.size() == 1) {
            message = getString(R.string.scan_report) + ": " + threats.size() + getString(R.string.threat_found);
        } else {
            message = getString(R.string.scan_report) + ": " + threats.size() + getString(R.string.threats_found);
        }
        Intent nextIntent = new Intent(this, MainActivity.class);
        nextIntent.putExtra(Constants.APP_STATE.DEEP_LINK, MenuProvider.SCAN_REPORT_TAG);
        nextIntent.setAction("dummyAction");
        PendingIntent pNextIntent = PendingIntent.getActivity(this, 0,
                nextIntent, PendingIntent.FLAG_CANCEL_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, Constants.NOTIFICATION_ID.SCHEDULED_SCAN_RESULT + "")
                .setContentTitle(getString(R.string.app_name_short))
                .setTicker(getString(R.string.app_name_short))
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pNextIntent)
                .setAutoCancel(true)
                .setDeleteIntent(pNextIntent)
                .build();

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        notificationManager.notify(0, notification);
    }


    public static void cancelNotification(Context ctx, int notifyId) {
        String ns = Context.NOTIFICATION_SERVICE;
        NotificationManager notification = (NotificationManager) ctx.getSystemService(ns);
        notification.cancel(notifyId);
    }

    private void serviceShutdown() {
        if (executorService != null) {
            executorService.shutdownNow();
            Log.e(TAG, "serviceShutdown: executor terminated " + executorService.isTerminated());
        }
        finish();
    }

    public static synchronized void setServiceVar(boolean running) {
        Log.e(TAG, "setServiceVar: " + running);
        serviceRunning = running;
    }

    public static synchronized boolean getServiceVar() {
        Log.e(TAG, "getServiceVar: " + serviceRunning);
        return serviceRunning;
    }

    private synchronized void interruptServiceIfIdle(boolean interrupt) {
        handler.removeCallbacksAndMessages(null);
        if (interrupt)
            handler.postDelayed(new interruptServiceRunnable(), INTERRUPT_INTERVAL);
    }

    private class interruptServiceRunnable implements Runnable {
        @Override
        public void run() {
            Log.e(TAG, "interruptServiceRunnable - interrupting...");
            serviceShutdown();
        }
    }

    private void findLatest(File root) {

        File[] list = root.listFiles();

        if (list == null) return;

        for (File f : list) {
            if (f.isDirectory()) {
                //Log.i("MOJ_DEBUG:","|ScanningService| findLatest " + f);
                findLatest(f);
            } else {
                if (latest == null) {
                    latest = f;
                    //Log.i("MOJ_DEBUG:","|ScanningService| findLatest1: " + f);
                } else if (f.lastModified() > latest.lastModified()) {
                    latest = f;
                    Log.i(TAG, "|ScanningService| findLatest2: " + f);
                }
            }
        }

    }
}
