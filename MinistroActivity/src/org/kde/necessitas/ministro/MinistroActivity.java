/*
    Copyright (c) 2011, BogDan Vatra <bog_dan_ro@yahoo.com>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.kde.necessitas.ministro;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.http.client.ClientProtocolException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.StatFs;
import android.provider.Settings;
import android.util.Log;


public class MinistroActivity extends Activity
{
    private static final String TAG = "MinistroActivity";
    private static final int CONNECTION_TIMEOUT = 20000; // 20 seconds for connection timeout
    private static final int READ_TIMEOUT = 10000; // 10 seconds for read timeout

    public native static int nativeChmode(String filepath, int mode);
    private static final String DOMAIN_NAME="https://files.kde.org/necessitas/ministro/android/necessitas/";

    private String[] m_modules;
    private int m_id=-1;
    private String m_qtLibsRootPath;
    private WakeLock m_wakeLock;
    
    private AlertDialog mCopyProgress;

    private CopyLibsTask mCopyLibsTask;
    
    private void checkNetworkAndDownload(final boolean update)
    {
        if (isOnline(this))
            new CheckLibraries().execute(update);
        else
        {
            AlertDialog.Builder builder = new AlertDialog.Builder(MinistroActivity.this);
            builder.setMessage(getResources().getString(R.string.ministro_network_access_msg));
            builder.setCancelable(true);
            builder.setNeutralButton(getResources().getString(R.string.settings_msg), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        final ProgressDialog m_dialog = ProgressDialog.show(MinistroActivity.this, null, getResources().getString(R.string.wait_for_network_connection_msg), true, true, new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface dialog)
                            {
                                finishMe();
                            }
                        });
                        getApplication().registerReceiver(new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent)
                            {
                                if (isOnline(MinistroActivity.this))
                                {
                                    try
                                    {
                                        getApplication().unregisterReceiver(this);
                                    }
                                    catch(Exception e)
                                    {
                                        e.printStackTrace();
                                    }
                                    runOnUiThread(new Runnable() {
                                        public void run()
                                        {
                                            m_dialog.dismiss();
                                            new CheckLibraries().execute(update);
                                        }
                                    });
                                }
                            }
                        }, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
                        try {
                            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                        } catch(Exception e) {
                            e.printStackTrace();
                            try {
                                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                            } catch(Exception e1) {

                                e1.printStackTrace();
                            }
                        }
                        dialog.dismiss();
                    }
                });
            builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id)
                    {
                            dialog.cancel();
                    }
                });
            builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                public void onCancel(DialogInterface dialog)
                {
                    finishMe();
                }
            });
            AlertDialog alert = builder.create();
            alert.show();
        }
    }
    private AlertDialog m_distSpaceDialog=null;
    private final int freeSpaceCode=0xf3ee500;
    private Semaphore m_diskSpaceSemaphore = new Semaphore(0);

    private boolean checkFreeSpace(final long size) throws InterruptedException
    {
        final StatFs stat = new StatFs(m_qtLibsRootPath);
        if (stat.getBlockSize() * stat.getAvailableBlocks() < size)
        {
            runOnUiThread(new Runnable() {
                public void run() {

                    AlertDialog.Builder builder = new AlertDialog.Builder(MinistroActivity.this);
                    builder.setMessage(getResources().getString(R.string.ministro_disk_space_msg,
                                                (size-(stat.getBlockSize() * stat.getAvailableBlocks()))/1024+"Kb"));
                    builder.setCancelable(true);
                    builder.setNeutralButton(getResources().getString(R.string.settings_msg), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                try {
                                    startActivityForResult(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS), freeSpaceCode);
                                } catch(Exception e) {
                                    e.printStackTrace();
                                    try {
                                        startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS), freeSpaceCode);
                                    } catch(Exception e1) {

                                        e1.printStackTrace();
                                    }
                                }
                            }
                        });
                    builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id)
                            {
                                dialog.dismiss();
                                m_diskSpaceSemaphore.release();
                            }
                        });
                    builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface dialog)
                        {
                            dialog.dismiss();
                            m_diskSpaceSemaphore.release();
                        }
                    });
                    m_distSpaceDialog = builder.create();
                    m_distSpaceDialog.show();
                }
            });
            m_diskSpaceSemaphore.acquire();
        }
        else
            return true;

        return stat.getBlockSize() * stat.getAvailableBlocks()>size;
    }

    protected void onActivityResult (int requestCode, int resultCode, Intent data)
    {
        if (requestCode == freeSpaceCode)
        {
            m_diskSpaceSemaphore.release();
            try
            {
                if (m_distSpaceDialog != null)
                {
                    m_distSpaceDialog.dismiss();
                    m_distSpaceDialog = null;
                }
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }
        }
    }
    private ServiceConnection m_ministroConnection=new ServiceConnection()
    {
        public void onServiceConnected(ComponentName name, IBinder service)
        {
            if (getIntent().hasExtra("id"))
                m_id = getIntent().getExtras().getInt("id");

            SharedPreferences preferences = getSharedPreferences("Ministro", MODE_PRIVATE);
            boolean copyLocalLibSuccess = preferences.getBoolean("COPY_LOCAL_LIBS_SUCCESS", false);
            boolean hasQtDir =  getFilesDir() != null && getFilesDir().list() != null && getFilesDir().list().length > 1;
            copyLocalLibSuccess = copyLocalLibSuccess && hasQtDir;
            Log.d(TAG, "m_ministroConnection.onServiceConnected()[m_id=" + m_id + ",copyLocalLibSuccess="
                    + copyLocalLibSuccess + "]");
            if (!copyLocalLibSuccess) {
                mCopyLibsTask = new CopyLibsTask();
                mCopyLibsTask.execute(new String[] {
                        "ministro_libs.zip", getFilesDir().getAbsolutePath()
                });
            } else {
                continueCheckupdate();
            }
        }

        public void onServiceDisconnected(ComponentName name)
        {
            Log.d(TAG, "onServiceDisconnected");
            m_ministroConnection = null;
        }
    };

    void finishMe()
    {
        if (-1 != m_id && null != MinistroService.instance())
            MinistroService.instance().retrievalFinished(m_id);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancelAll();
        finish();
    }

    private static URL getVersionUrl(Context c) throws MalformedURLException
    {
        return new URL(DOMAIN_NAME+MinistroService.getRepository(c)+"/"+android.os.Build.CPU_ABI+"/android-"+android.os.Build.VERSION.SDK_INT+"/versions.xml");
    }

    private static URL getLibsXmlUrl(Context c, String version) throws MalformedURLException
    {
        return new URL(DOMAIN_NAME+MinistroService.getRepository(c)+"/"+android.os.Build.CPU_ABI+"/android-"+android.os.Build.VERSION.SDK_INT+"/libs-"+version+".xml");
    }

    public static boolean isOnline(Context c)
    {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        if (netInfo != null && netInfo.isConnectedOrConnecting())
            return true;
        return false;
    }

    private static String deviceSupportedFeatures(String supportedFeatures)
    {
        if (null==supportedFeatures)
            return "";
        String [] serverFeaturesList=supportedFeatures.trim().split(" ");
        String [] deviceFeaturesList=null;
        try {
            FileInputStream fstream = new FileInputStream("/proc/cpuinfo");
            DataInputStream in = new DataInputStream(fstream);
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String strLine;
            while ((strLine = br.readLine()) != null)
            {
                if (strLine.startsWith("Features"))
                {
                    deviceFeaturesList=strLine.substring(strLine.indexOf(":")+1).trim().split(" ");
                    break;
                }
            }
            br.close();
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }

        String features="";
        for(String sfeature: serverFeaturesList)
            for (String dfeature: deviceFeaturesList)
                if (sfeature.equals(dfeature))
                    features+="_"+dfeature;

        return features;
    }


    public static double downloadVersionXmlFile(Context c, boolean checkOnly)
    {
        if (!isOnline(c))
            return-1;
        try
        {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document dom = null;
            Element root = null;
            URLConnection connection = getVersionUrl(c).openConnection();
            connection.setConnectTimeout(CONNECTION_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            dom = builder.parse(connection.getInputStream());
            root = dom.getDocumentElement();
            root.normalize();
            double version = Double.valueOf(root.getAttribute("latest"));
            if ( MinistroService.instance().getVersion() >= version )
                return MinistroService.instance().getVersion();

            if (checkOnly)
                return version;
            String supportedFeatures=null;
            if (root.hasAttribute("features"))
                supportedFeatures=root.getAttribute("features");
            connection = getLibsXmlUrl(c, version+deviceSupportedFeatures(supportedFeatures)).openConnection();
            File file= new File(MinistroService.instance().getVersionXmlFile());
            file.delete();
            FileOutputStream outstream = new FileOutputStream(MinistroService.instance().getVersionXmlFile());
            InputStream instream = connection.getInputStream();
            byte[] tmp = new byte[2048];
            int downloaded;
            while ((downloaded = instream.read(tmp)) != -1)
                outstream.write(tmp, 0, downloaded);

            outstream.close();
            MinistroService.instance().refreshLibraries(false);
            return version;
        } catch (ClientProtocolException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

    private class DownloadManager extends AsyncTask<Library, Integer, Long>
    {
        private ProgressDialog m_dialog = null;
        private String m_status = getResources().getString(R.string.start_downloading_msg);
        private int m_totalSize=0, m_totalProgressSize=0;

        @Override
        protected void onPreExecute()
        {
            m_dialog = new ProgressDialog(MinistroActivity.this);
            m_dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            m_dialog.setTitle(getResources().getString(R.string.downloading_qt_libraries_msg));
            m_dialog.setMessage(m_status);
            m_dialog.setCancelable(true);
            m_dialog.setCanceledOnTouchOutside(false);
            m_dialog.setOnCancelListener(new DialogInterface.OnCancelListener(){
                public void onCancel(DialogInterface dialog)
                {
                    DownloadManager.this.cancel(false);
                    finishMe();
                }
            });
            try
            {
                m_dialog.show();
            }
            catch(Exception e)
            {
                e.printStackTrace();
                m_dialog = null;
            }
            super.onPreExecute();
        }

        private boolean DownloadItem(String url, String file, long size, String fileSha1) throws NoSuchAlgorithmException, MalformedURLException, IOException
        {
            for (int i=0;i<2;i++)
            {
                MessageDigest digester = MessageDigest.getInstance("SHA-1");
                URLConnection connection = new URL(url).openConnection();
                Library.mkdirParents(m_qtLibsRootPath, file, 1);
                String filePath=m_qtLibsRootPath+file;
                int progressSize=0;
                try
                {
                    FileOutputStream outstream = new FileOutputStream(filePath);
                    InputStream instream = connection.getInputStream();
                    int downloaded;
                    byte[] tmp = new byte[2048];
                    int oldProgress=-1;
                    while ((downloaded = instream.read(tmp)) != -1)
                    {
                        if (isCancelled())
                            break;
                        progressSize+=downloaded;
                        m_totalProgressSize+=downloaded;
                        digester.update(tmp, 0, downloaded);
                        outstream.write(tmp, 0, downloaded);
                        int progress=(int)(progressSize*100/size);
                        if (progress!=oldProgress)
                        {
                            publishProgress(progress
                                    , m_totalProgressSize);
                            oldProgress = progress;
                        }
                    }
                    String sha1 =  Library.convertToHex(digester.digest());
                    if (sha1.equalsIgnoreCase(fileSha1))
                    {
                        outstream.close();
                        nativeChmode(filePath, 0644);
                        return true;
                    }
                    else
                        Log.e("Ministro", "sha1 mismatch, the file:"+file+" will be removed, expected sha1:"+fileSha1+" got sha1:"+sha1+" file was downloaded from "+url);
                    outstream.close();
                    File f = new File(filePath);
                    f.delete();
                } catch (Exception e) {
                    e.printStackTrace();
                    File f = new File(filePath);
                    f.delete();
                }
                m_totalProgressSize-=progressSize;
            }
            return false;
        }

        void removeFile(String file)
        {
            File f = new File(m_qtLibsRootPath+file);
            f.delete();
        }

        @Override
        protected Long doInBackground(Library... params)
        {
            try
            {
                for (int i=0;i<params.length;i++)
                {
                    m_totalSize+=params[i].size;
                    if (null != params[i].needs)
                        for (int j=0;j<params[i].needs.length;j++)
                            m_totalSize+=params[i].needs[j].size;
                }
                m_dialog.setMax(m_totalSize);
                if (!checkFreeSpace(m_totalSize))
                    return null;
                for (int i=0;i<params.length;i++)
                {
                    if (isCancelled())
                        break;
                    synchronized (m_status)
                    {
                        m_status=params[i].name+" ";
                    }
                    publishProgress(0, m_totalProgressSize);
                    if (!DownloadItem(params[i].url, params[i].filePath, params[i].size, params[i].sha1))
                        break;

                    if (null != params[i].needs)
                        for (int j=0;j<params[i].needs.length;j++)
                        {
                            synchronized (m_status)
                            {
                                m_status=params[i].needs[j].name+" ";
                            }
                            publishProgress(0, m_totalProgressSize);
                            if (!DownloadItem(params[i].needs[j].url, params[i].needs[j].filePath, params[i].needs[j].size, params[i].needs[j].sha1))
                            {
                                for (int k=0;k<j;k++) // remove previous neede files
                                    removeFile(params[i].needs[k].filePath);
                                removeFile(params[i].filePath); // remove the parent
                                break;
                            }
                        }
                }
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(Integer... values)
        {
            try
            {
                if (m_dialog != null)
                {
                    synchronized (m_status)
                    {
                        m_dialog.setMessage(m_status+values[0]+"%");
                        m_dialog.setProgress(values[1]);
                    }
                }
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }
            super.onProgressUpdate(values);
        }

        @Override
        protected void onPostExecute(Long result)
        {
            super.onPostExecute(result);
            if (m_dialog != null)
            {
                m_dialog.dismiss();
                m_dialog = null;
            }
            MinistroService.instance().refreshLibraries(false);
            finishMe();
        }
    }

    private class CheckLibraries extends AsyncTask<Boolean, String, Double>
    {
        private ProgressDialog m_dialog = null;
        private final ArrayList<Library> newLibs = new ArrayList<Library>();
        private String m_message;
        @Override
        protected void onPreExecute()
        {
            try
            {
                m_dialog = ProgressDialog.show(MinistroActivity.this, null,
                        getResources().getString(R.string.checking_libraries_msg), true, true);
            }
            catch (Exception e)
            {
                e.printStackTrace();
                m_dialog = null;
            }
            super.onPreExecute();
        }

        @Override
        protected Double doInBackground(Boolean... update)
        {
            double version=0.0;
            try
            {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document dom = null;
                Element root = null;
                double oldVersion=MinistroService.instance().getVersion();
                if (update[0] || MinistroService.instance().getVersion()<0)
                    version = downloadVersionXmlFile(MinistroActivity.this, false);
                else
                    version = MinistroService.instance().getVersion();

                SharedPreferences preferences=getSharedPreferences("Ministro", MODE_PRIVATE);
                // extract device look&feel
                if (!preferences.getString("CODENAME", "").equals(android.os.Build.VERSION.CODENAME) ||
                        !preferences.getString("INCREMENTAL", "").equals(android.os.Build.VERSION.INCREMENTAL) ||
                        !preferences.getString("RELEASE", "").equals(android.os.Build.VERSION.RELEASE) ||
                        !preferences.getString("MINISTRO_VERSION", "").equals(getPackageManager().getPackageInfo(getPackageName(), 0).versionName) ||
                        !(new File(m_qtLibsRootPath+"style").exists()))
                {
                    m_message = getResources().getString(R.string.extracting_look_n_feel_msg);
                    publishProgress(m_message);
                    new ExtractStyle(MinistroActivity.this, m_qtLibsRootPath+"style/");
                    SharedPreferences.Editor editor= preferences.edit();
                    editor.putString("MINISTRO_VERSION",getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
                    editor.commit();
                }

                ArrayList<Library> libraries;
                if (update[0])
                {
                    if (oldVersion!=version)
                        libraries = MinistroService.instance().getDownloadedLibraries();
                    else
                        return version;
                }
                else
                    libraries = MinistroService.instance().getAvailableLibraries();

                ArrayList<String> notFoundModules = new ArrayList<String>();
                if (m_modules!=null)
                    MinistroService.instance().checkModules(m_modules, notFoundModules);

                dom = builder.parse(new FileInputStream(MinistroService.instance().getVersionXmlFile()));

                factory = DocumentBuilderFactory.newInstance();
                builder = factory.newDocumentBuilder();
                root = dom.getDocumentElement();
                root.normalize();

                // extract device root certificates
                if (!preferences.getString("CODENAME", "").equals(android.os.Build.VERSION.CODENAME) ||
                        !preferences.getString("INCREMENTAL", "").equals(android.os.Build.VERSION.INCREMENTAL) ||
                        !preferences.getString("RELEASE", "").equals(android.os.Build.VERSION.RELEASE))
                {
                    m_message = getResources().getString(R.string.extracting_SSL_msg);
                    publishProgress(m_message);
                    String environmentVariables=root.getAttribute("environmentVariables");
                    environmentVariables=environmentVariables.replaceAll("MINISTRO_PATH", "");
                    String environmentVariablesList[]=environmentVariables.split("\t");
                    for (int i=0;i<environmentVariablesList.length;i++)
                    {
                        String environmentVariable[]=environmentVariablesList[i].split("=");
                        if (environmentVariable[0].equals("MINISTRO_SSL_CERTS_PATH"))
                        {
                            String path=Library.mkdirParents(getFilesDir().getAbsolutePath(),environmentVariable[1], 0);
                            Library.removeAllFiles(path);
                            try
                            {
                                KeyStore ks = null;
                                if (Build.VERSION.SDK_INT>13)
                                {
                                    ks = KeyStore.getInstance("AndroidCAStore");
                                    ks.load(null, null);
                                }
                                else
                                {
                                    ks= KeyStore.getInstance(KeyStore.getDefaultType());
                                    String cacertsPath=System.getProperty("javax.net.ssl.trustStore");
                                    if (null == cacertsPath)
                                        cacertsPath="/system/etc/security/cacerts.bks";
                                    FileInputStream instream = new FileInputStream(new File(cacertsPath));
                                    ks.load(instream, null);
                                }

                                for (Enumeration<String> aliases = ks.aliases(); aliases.hasMoreElements(); )
                                {
                                    String aName = aliases.nextElement();
                                    try
                                    {
                                        X509Certificate cert=(X509Certificate) ks.getCertificate(aName);
                                        if (null==cert)
                                            continue;
                                        String filePath=path+"/"+cert.getType()+"_"+cert.hashCode()+".der";
                                        FileOutputStream outstream = new FileOutputStream(new File(filePath));
                                        byte buff[]=cert.getEncoded();
                                        outstream.write(buff, 0, buff.length);
                                        outstream.close();
                                        nativeChmode(filePath, 0644);
                                    } catch(KeyStoreException e) {
                                        e.printStackTrace();
                                    } catch(Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                            } catch (KeyStoreException e) {
                                e.printStackTrace();
                            } catch (IOException e) {
                                e.printStackTrace();
                            } catch (NoSuchAlgorithmException e) {
                                e.printStackTrace();
                            } catch (CertificateException e) {
                                e.printStackTrace();
                            }
                            SharedPreferences.Editor editor= preferences.edit();
                            editor.putString("CODENAME",android.os.Build.VERSION.CODENAME);
                            editor.putString("INCREMENTAL", android.os.Build.VERSION.INCREMENTAL);
                            editor.putString("RELEASE", android.os.Build.VERSION.RELEASE);
                            editor.commit();
                            break;
                        }
                    }
                }

                Node node = root.getFirstChild();
                while(node != null)
                {
                    if (node.getNodeType() == Node.ELEMENT_NODE)
                    {
                        Library lib= Library.getLibrary((Element)node, true);
                        if (update[0])
                        { // check for updates
                            for (int j=0;j<libraries.size();j++)
                                if (libraries.get(j).name.equals(lib.name))
                                {
                                    newLibs.add(lib);
                                    break;
                                }
                        }
                        else
                        {// download missing libraries
                            for(String module : notFoundModules)
                                if (module.equals(lib.name))
                                {
                                    newLibs.add(lib);
                                    break;
                                }
                        }
                    }

                    // Workaround for an unbelievable bug !!!
                    try {
                        node = node.getNextSibling();
                    } catch (Exception e) {
                        e.printStackTrace();
                        break;
                    }
                }
                return version;
            } catch (ClientProtocolException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (ParserConfigurationException e) {
                e.printStackTrace();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            }
            return -1.;
        }

        @Override
        protected void onProgressUpdate(String... messages)
        {
            try
            {
                if (null != m_dialog)
                    m_dialog.setMessage(messages[0]);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            super.onProgressUpdate(messages);
        }

        @Override
        protected void onPostExecute(Double result)
        {
            try
            {
                if (null != m_dialog)
                {
                    m_dialog.dismiss();
                    m_dialog = null;
                }
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }
            if (newLibs.size()>0 && result>0)
            {
                Library[] libs = new Library[newLibs.size()];
                libs = newLibs.toArray(libs);
                new DownloadManager().execute(libs);
            }
            else
                finishMe();
            super.onPostExecute(result);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        m_qtLibsRootPath = getFilesDir().getAbsolutePath()+"/qt/";
        File dir=new File(m_qtLibsRootPath);
        dir.mkdirs();
        nativeChmode(m_qtLibsRootPath, 0755);
        bindService(new Intent("org.kde.necessitas.ministro.IMinistro"), m_ministroConnection, Context.BIND_AUTO_CREATE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        m_wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "Ministro");
        m_wakeLock.acquire();
    }

    @Override
    protected void onDestroy()
    {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        if (null != m_wakeLock)
        {
            m_wakeLock.release();
            m_wakeLock = null;
        }
        unbindService(m_ministroConnection);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig)
    {
        //Avoid activity from being destroyed/created
        super.onConfigurationChanged(newConfig);
    }

    private class CopyLibsTask extends AsyncTask<String, String, Boolean> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            showCopyProgress();
        }
        
        @Override
        protected Boolean doInBackground(String... params) {
            if (params == null || params.length < 2) {
                return false;
            }
            String filePath = params[0];
            String dstDirPath = params[1];
            File dstDir = new File(dstDirPath);
            if (!dstDir.exists()) {
                dstDir.mkdirs();
                nativeChmode(dstDir.getAbsolutePath(), 0755);
            }
            ZipInputStream zis = null;
            try {
                InputStream is = getAssets().open(filePath);
                zis = new ZipInputStream(is);
                ZipEntry entry = null;
                while ((entry = zis.getNextEntry()) != null) {
                    if(isCancelled()){
                        return false;
                    }
                    File fileDst = new File(dstDirPath, entry.getName());
                    if (entry.isDirectory()) {
                        if (!fileDst.exists())
                            fileDst.mkdirs();
                            nativeChmode(fileDst.getAbsolutePath(), 0755);
                    } else {
                        if(fileDst.exists()){
                            fileDst.delete();
                            fileDst.createNewFile();
                        }
                        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(
                                fileDst));
                        try {
                            byte buf[] = new byte[1024 * 1024 * 5];
                            int tmpLen = -1;
                            while ((tmpLen = zis.read(buf)) != -1) {
                                bos.write(buf, 0, tmpLen);
                            }
                            bos.flush();
                            nativeChmode(fileDst.getAbsolutePath(), 0644);
                        } finally {
                            if (bos != null) {
                                bos.close();
                            }
                        }
                    }
                    publishProgress(entry.getName());
                }
                publishProgress("DONE");
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    if (zis != null) {
                        zis.close();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return true;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            if (values == null || values.length == 0) {
                return;
            }
            if (values[0] != null && values[0].equals("DONE")) {
                mCopyProgress.cancel();
                return;
            }
            if (values[0] != null && mCopyProgress != null) {
                mCopyProgress.setMessage(String.valueOf(values[0]));
            }
        }
        
        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            if(result){
                try {
                    SharedPreferences preferences = getSharedPreferences("Ministro", MODE_PRIVATE);
                    SharedPreferences.Editor editor = preferences.edit();
                    editor.putString("CODENAME", android.os.Build.VERSION.CODENAME);
                    editor.putString("MINISTRO_VERSION",
                            getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
                    editor.putLong("LASTCHECK", System.currentTimeMillis());
                    editor.putString("INCREMENTAL", android.os.Build.VERSION.INCREMENTAL);
                    editor.putString("RELEASE", android.os.Build.VERSION.RELEASE);
                    editor.putBoolean("COPY_LOCAL_LIBS_SUCCESS", true);
                    editor.commit();
                    MinistroService.instance().refreshLibraries(false);
                    finishMe();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

    }

    
    private void showCopyProgress(){
        if(mCopyProgress == null){
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.copy_dialog_title);
            builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    mCopyProgress.cancel();
                }
            });
            builder.setCancelable(true);
            builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                
                @Override
                public void onCancel(DialogInterface dialog) {
                    // TODO Auto-generated method stub
                    Log.d(TAG, "showCopyProgress.onCancel()");
                    if(mCopyLibsTask != null){
                        mCopyLibsTask.cancel(true);
                        finishMe();
                        Log.d(TAG, "showCopyProgress()[mCopyLibsTask."+mCopyLibsTask.isCancelled()+"]");
                    }
                }
            });
            mCopyProgress = builder.create();
            mCopyProgress.setMessage(getString(R.string.copy_dialog_msg_loading));
            mCopyProgress.setCanceledOnTouchOutside(false);
        }
        mCopyProgress.show();
    }
    
    private void continueCheckupdate()
    {
        if (getIntent().hasExtra("modules"))
        {
            m_modules = getIntent().getExtras().getStringArray("modules");
            AlertDialog.Builder builder = new AlertDialog.Builder(MinistroActivity.this);
            builder.setMessage(getResources().getString(R.string.download_app_libs_msg,
                    getIntent().getExtras().getString("name")))
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.yes,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int id) {
                                    dialog.dismiss();
                                    checkNetworkAndDownload(false);
                                }
                            })
                    .setNegativeButton(android.R.string.no,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int id) {
                                    dialog.cancel();
                                    finishMe();
                                }
                            });
            AlertDialog alert = builder.create();
            try
            {
                alert.show();
            }
            catch (Exception e)
            {
                e.printStackTrace();
                checkNetworkAndDownload(false);
            }
        }
        else
            checkNetworkAndDownload(true);
    }
    
    @Override
    public void onBackPressed() {
        if(mCopyProgress != null && mCopyProgress.isShowing()){
            mCopyProgress.cancel();
        }
        super.onBackPressed();
    }
    
    static
    {
        System.loadLibrary("ministro");
    }
}
