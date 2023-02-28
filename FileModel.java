public class FileModel {

    private static final String TAG = FileModel.class.getSimpleName();
    public static final String APPS_DIR = "APPS_DIR";
    public static final String ROOT_DIR = "ROOT_DIR";

    private static final List<String> appExtensions = Arrays.asList(".apk");
    private static final List<String> musicExtensions = Arrays.asList(".mp3", ".wma", ".wav", ".m4a");
    private static final List<String> imagesExtensions = Arrays.asList(".jpg", ".jpeg", ".bmp", ".png");
    private static final List<String> videosExtensions = Arrays.asList(".mp4", ".3gp", ".mkv", ".flv", ".avi", ".mov", ".f4v");

    public static List<FileModel> appsCache = new ArrayList<>();

    public  static int filesCountForScan;

    private static final int FOLDER = R.drawable.ic_folder;
    private static final int IMAGE = 1;
    private static final int VIDEO = R.drawable.ic_video;
    private static final int MUSIC = R.drawable.ic_music;
    private static final int OTHER_FILE = R.drawable.ic_other_file;


    private String name;
    private String path;
    private int iconRes;
    private boolean selected;
    private boolean directory;
    private boolean installedApk;

    public FileModel() {
    }

    public FileModel(String name, int iconRes, String path, boolean selected, boolean directory, boolean installedApk) {
        this.name = name;
        this.iconRes = iconRes;
        this.path = path;
        this.selected = selected;
        this.directory = directory;
        this.installedApk = installedApk;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getIconRes() {
        if (directory) {
            return FOLDER;
        } else if (checkIfAudioFile()) {
            return MUSIC;
        } else if (checkIfVideoFile()) {
            return VIDEO;
        } else if (checkIfImageFile()) {
            return IMAGE;
        }
        return OTHER_FILE;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isDirectory() {
        return directory;
    }

    public void setDirectory(boolean directory) {
        this.directory = directory;
    }

    public boolean isInstalledApk() {
        return installedApk;
    }

    public void setInstalledApk(boolean installedApk) {
        this.installedApk = installedApk;
    }

    private boolean checkIfAudioFile() {
        if (path != null && path.contains(".")) {
            int lastDot = path.lastIndexOf(".");
            String end = path.substring(lastDot, path.length());
            if (musicExtensions.contains(end.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean checkIfImageFile() {
        if (path != null && path.contains(".")) {
            int lastDot = path.lastIndexOf(".");
            String end = path.substring(lastDot, path.length());
            if (imagesExtensions.contains(end.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean checkIfVideoFile() {
        if (path != null && path.contains(".")) {
            int lastDot = path.lastIndexOf(".");
            String end = path.substring(lastDot, path.length());
            if (videosExtensions.contains(end.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    public static List<FileModel> getChildrenFileModels(Context context, FileModel fileModel) {
        File file;
        final List<FileModel> children = new ArrayList<>();
        if (fileModel.getPath().equals(ROOT_DIR)) {
            FileModel model1 = new FileModel();
            model1.setName(context.getString(R.string.device_storage));
            model1.setPath(Environment.getExternalStorageDirectory().getAbsolutePath());
            model1.setDirectory(true);
            children.add(model1);

            FileModel model2 = new FileModel();
            model2.setName(context.getString(R.string.sd_memory_card));
            model2.setPath(getExternalStorageDirectories(context)[0]);
            model2.setDirectory(true);
            children.add(model2);

        } else if (fileModel.getPath().equals(APPS_DIR)) {
            if(appsCache != null && appsCache.size() > 0){
                for(FileModel item : appsCache){
                    children.add(item);
                }
            }else{
                final PackageManager pm = AVApplication.getContext().getPackageManager();
                List<ApplicationInfo> items = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                if (items.size() > 0) {
                    for (ApplicationInfo child : items) {
                        if ((child.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                            String installer = pm.getInstallerPackageName(child.packageName);
                            FileModel model = new FileModel();
                            model.setName(pm.getApplicationLabel(child).toString());
                            model.setPath(child.sourceDir);
                            model.setDirectory(false);
                            model.setInstalledApk(true);
                            appsCache.add(model);
                            children.add(model);
                        }
                    }
                }
            }

        } else {
            file = new File(fileModel.getPath());
            File[] files = file.listFiles();
            if (files != null && files.length > 0) {
                for (File child : files) {
                    FileModel model = new FileModel();
                    model.setName(child.getName());
                    model.setPath(child.getAbsolutePath());
                    model.setDirectory(child.isDirectory());
                    children.add(model);
                }
            }
            if (fileModel.getPath().equals(Environment.getExternalStorageDirectory().getAbsolutePath())) {
                FileModel model = new FileModel();
                model.setName(APPS_DIR);
                model.setPath(APPS_DIR);
                model.setDirectory(true);
                children.add(0, model);
            }
        }
        return children;
    }

    public static FileModel getRootModel(Context context) {

        SharedPreferences prefs = context.getSharedPreferences(Constants.APP_STATE.STATE_SHARED_PREFERENCES, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(Constants.APP_STATE.EXTERNAL_SD_CARDS, 1);

        FileModel rootModel = new FileModel(null, -1, ROOT_DIR, true, false, false);

        String[] sdCardPaths = getExternalStorageDirectories(context);

        if (sdCardPaths.length == 0) {
            editor.putInt(Constants.APP_STATE.EXTERNAL_SD_CARDS, 0);
            editor.commit();
            return new FileModel(null, -1, Environment.getExternalStorageDirectory().getAbsolutePath(), true, true, false);
        } else {
            File f_secs = new File(sdCardPaths[0]);
            if (f_secs.listFiles() == null || f_secs.listFiles().length == 0) {
                editor.putInt(Constants.APP_STATE.EXTERNAL_SD_CARDS, 0);
                editor.commit();
                return new FileModel(null, -1, Environment.getExternalStorageDirectory().getAbsolutePath(), true, true, false);
            }
        }
        editor.commit();
        return rootModel;
    }

    public static List<FileScanWrapper> getFilesSelectedForScan(Context context, boolean scanAll) {

        List<FileScanWrapper> filesForScanning;
        List<FileModel> rootModels = new ArrayList<>();
        if (!scanAll) {
            Log.i("MOJ_DEBUG: ","|FileMOdel| getFilesSelectedForScan");
            ScanPreferencesDatabaseHelper db = ScanPreferencesDatabaseHelper.GET_INSTANCE(AVApplication.getContext());
            rootModels = db.getSelectedFiles();
        }

        if (rootModels.size() == 0) {
            rootModels.add(FileModel.getRootModel(AVApplication.getContext()));
        }
        filesForScanning = getFiles(rootModels, context);

        return filesForScanning;
    }

    private static List<FileScanWrapper> getFiles(List<FileModel> models, Context context) {
        List<FileScanWrapper> roots = new ArrayList<>();
        for (FileModel model : models) {
            Log.e(TAG, "getFiles: " + model.getPath());
            if (model.getPath().equals(ROOT_DIR)) {
                addApps(context, roots);
                getFilesOnly(Environment.getExternalStorageDirectory(), roots);
                File external = new File(getExternalStorageDirectories(context)[0]);
                getFilesOnly(external, roots);
            } else if (model.getPath().equals(Environment.getExternalStorageDirectory().getAbsolutePath())) {
                addApps(context, roots);
                getFilesOnly(Environment.getExternalStorageDirectory(), roots);
            } else if (model.getPath().equals(APPS_DIR)) {
                addApps(context, roots);
            } else {
                File file = new File(model.getPath());
                if (file.isDirectory() && file.listFiles().length > 0) {
                    getFilesOnly(file, roots);
                } else {
                    if (model.isInstalledApk())
                        roots.add(new FileScanWrapper(file, true));
                    else
                        roots.add(new FileScanWrapper(file, false));
                }
            }
        }
        return roots;
    }

    public static void getFilesOnly(File root, List<FileScanWrapper> accumulator) {
        if(!ScanningService.scanInterrupted) {
        File[] listOfFiles = root.listFiles();
        if (listOfFiles != null && listOfFiles.length > 0) {
            for (File f : listOfFiles) {
                if (f.isDirectory()) {
                    getFilesOnly(f, accumulator);
                } else {
                    FileScanWrapper fileWrapper = new FileScanWrapper(f, false);
                    accumulator.add(fileWrapper);
                    filesCountForScan = accumulator.size();
                    BusProvider.post(new ScanInitProgressEvent(filesCountForScan));
                }

                //Log.i("MOJ_DEBUG: ","|FileModel| getFilesOnly: F " + accumulator.size());

               // Log.i("MOJ_DEBUG: ","|FileModel| getFilesOnly: F " + accumulator.size());
            }
        }
        //BusProvider.post(new ScanInitFinishedEvent(filesCountForScan));
    }
    }
    public static int getFilesCountForScan(){
        return filesCountForScan;
    }
    public static int getFilesCount(List<FileScanWrapper> accumulator){

        return accumulator.size();
    }

    private static void addApps(Context context, List<FileScanWrapper> files) {
        final PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> items = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo item : items) {
            if ((item.flags & ApplicationInfo.FLAG_SYSTEM) == 0 && !WhiteList.hashSet.contains(item.packageName)) {
                File app = new File(item.sourceDir);
                FileScanWrapper appWrapper = new FileScanWrapper(app, true);
                files.add(appWrapper);
            }
        }
    }

    /* returns external storage paths (directory of external memory card) as array of Strings */
    public static String[] getExternalStorageDirectories(Context context) {
        List<String> results = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) { //Method 1 for KitKat & above
            File[] externalDirs = context.getExternalFilesDirs(null);
            try {
                for (File file : externalDirs) {
                    String path = "";
                    path = file.getPath().split("/Android")[0];
                    boolean addPath = false;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        addPath = Environment.isExternalStorageRemovable(file);
                    } else {
                        addPath = Environment.MEDIA_MOUNTED.equals(EnvironmentCompat.getStorageState(file));
                    }

                    if (addPath && !path.equals(Environment.getExternalStorageDirectory().getAbsolutePath())) {
                        results.add(path);
                    }
                }
            } catch (Exception e) {

            }
        }

        if (System.getenv("SECONDARY_STORAGE") != null && !containsEnv(results)) {
            results.add(System.getenv("SECONDARY_STORAGE"));
        }


        if (results.isEmpty()) { //Method 2 for all versions
            // better variation of: http://stackoverflow.com/a/40123073/5002496
            String output = "";
            try {
                final Process process = new ProcessBuilder().command("mount | grep /dev/block/vold")
                        .redirectErrorStream(true).start();
                process.waitFor();
                final InputStream is = process.getInputStream();
                final byte[] buffer = new byte[1024];
                while (is.read(buffer) != -1) {
                    output = output + new String(buffer);
                }
                is.close();
            } catch (final Exception e) {
                e.printStackTrace();
            }
            if (!output.trim().isEmpty()) {
                String devicePoints[] = output.split("\n");
                for (String voldPoint : devicePoints) {
                    results.add(voldPoint.split(" ")[2]);
                }
            }
        }

        //Below few lines is to remove paths which may not be external memory card, like OTG (feel free to comment them out)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (int i = 0; i < results.size(); i++) {
                if (!results.get(i).toLowerCase().matches(".*[0-9a-f]{4}[-][0-9a-f]{4}")) {
                    Log.d(TAG, results.get(i) + " might not be extSDcard");
                    results.remove(i--);
                }
            }
        } else {
            for (int i = 0; i < results.size(); i++) {
                if (!results.get(i).toLowerCase().contains("ext") && !results.get(i).toLowerCase().contains("sdcard")) {
                    Log.d(TAG, results.get(i) + " might not be extSDcard");
                    results.remove(i--);
                }
            }
        }

        String[] storageDirectories = new String[results.size()];
        for (int i = 0; i < results.size(); ++i) storageDirectories[i] = results.get(i);

        return storageDirectories;
    }

    private static boolean containsEnv(List<String> results) {
        boolean containsEnv = false;
        if (results.size() > 0) {
            for (String path : results) {
                if (path.equals(System.getenv("SECONDARY_STORAGE"))) {
                    containsEnv = true;
                }
            }
        }
        return containsEnv;
    }

}
