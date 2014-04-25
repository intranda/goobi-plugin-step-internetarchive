package de.intranda.goobi.plugins;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.Namespace;
import org.jdom.input.SAXBuilder;

import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedAsChildException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsMods;

@SuppressWarnings("deprecation")
public class InternetArchiveParser {
    private static final Logger logger = Logger.getLogger(InternetArchiveParser.class);

    private static boolean useProxy = false;

    // TODO importJournal und importMonograph zusammenlegen oder duplizierten Code in eigene Methode auslagern

    public static void main(String[] args) {

        Options options = generateOptions();
        CommandLineParser parser = new PosixParser();
        try {
            CommandLine cl = parser.parse(options, args);

            if (cl.hasOption("h")) {
                HelpFormatter hf = new HelpFormatter();
                hf.setWidth(79);
                hf.printHelp("java -jar InternetArchiveParser.jar [options]", options);
                return;
            }

            if (!cl.hasOption("i") || cl.getOptionValue("i").equals("")) {
                System.err.println("Process id is missing.");
                HelpFormatter hf = new HelpFormatter();
                hf.setWidth(79);
                hf.printHelp("java -jar InternetArchiveParser.jar [options]", options);
                return;
            }

            String config = "/opt/digiverso/goobi/scripts/internetarchive.properties";
            if (cl.hasOption("c") && !cl.getOptionValue("c").equals("")) {
                config = cl.getOptionValue("cf");
            }

            logger.debug("loading configuration file " + config);

            String processid = cl.getOptionValue("i");
            logger.debug("started internet archive parser for process " + processid);
            Helper h = new Helper(config, processid);
            useProxy = h.getUseProxy();
            if (cl.getOptionValue("o").equalsIgnoreCase("download")) {
                logger.debug("started internet archive parser with download option for journals");
                if (!writeIdentifier(h, null)) {
                    System.err.println("Can not write identifier list.");
                    return;
                }
                if (!download(h)) {
                    System.err.println("Can not download data.");
                    return;
                }
            } else if (cl.getOptionValue("o").equalsIgnoreCase("downloadbook")) {
                logger.debug("started internet archive parser with download option for monographs");
                if (!cl.hasOption("n") || cl.getOptionValue("n").equals("")) {
                    System.err.println("Name is missing.");
                    HelpFormatter hf = new HelpFormatter();
                    hf.setWidth(79);
                    hf.printHelp("java -jar InternetArchiveParser.jar [options]", options);
                    return;
                }
                String name = cl.getOptionValue("n");
                if (!writeIdentifier(h, name)) {
                    System.err.println("Can not write identifier list.");
                    return;
                }
                if (!download(h)) {
                    System.err.println("Can not download data.");
                    return;
                }
            }

            else if (cl.getOptionValue("o").equalsIgnoreCase("import")) {

                logger.debug("started internet archive parser with import option");

                if (!cl.hasOption("r") || cl.getOptionValue("r").equals("")) {
                    System.err.println("Ruleset is missing.");
                    HelpFormatter hf = new HelpFormatter();
                    hf.setWidth(79);
                    hf.printHelp("java -jar InternetArchiveParser.jar [options]", options);
                    return;
                }
                String name = null;
                if (cl.hasOption("n") && !cl.getOptionValue("n").equals("")) {
                    name = cl.getOptionValue("n");

                }
                String prefsname = cl.getOptionValue("r");
                try {
                    if (!importData(h, prefsname, processid, name)) {
                        System.err.println("Can not import data.");
                        return;
                    }
                } catch (PreferencesException e) {
                    logger.error(e);
                } catch (ReadException e) {
                    logger.error(e);
                }
            } else {
                HelpFormatter hf = new HelpFormatter();
                hf.setWidth(79);
                hf.printHelp("java -jar InternetArchiveParser.jar [options]", options);
            }

        } catch (ParseException e) {
            logger.error(e);
            System.err.println(e.getMessage());
            HelpFormatter hf = new HelpFormatter();
            hf.setWidth(79);
            hf.printHelp("java -jar InternetArchiveParser.jar [options]", options);
            return;
        }

    }

    private static boolean importData(Helper h, String prefsname, String processid, String internetArchiveID) throws PreferencesException,
            ReadException {
        Prefs prefs = new Prefs();
        prefs.loadPrefs(prefsname);
        logger.debug("Ruleset is " + prefsname);

        Fileformat ff = new MetsMods(prefs);
        ff.read("/opt/digiverso/goobi/metadata/" + processid + "/meta.xml");

        logger.debug("read mets file for " + processid);

        DocStruct root = ff.getDigitalDocument().getLogicalDocStruct();
        if (root.getType().getName().equalsIgnoreCase("Periodical")) {
            return importJournal(h, root, ff, prefs, processid);
        } else {
            return importMonograph(h, root, ff, prefs, processid, internetArchiveID);
        }

    }

    private static boolean importMonograph(Helper h, DocStruct monograph, Fileformat ff, Prefs prefs, String processid, String internetArchiveID)
            throws PreferencesException {

        String foldername = "/opt/digiverso/goobi/metadata/" + processid + "/images/source/";
        logger.debug("import " + internetArchiveID);

        File folder = new File(foldername);
        if (!folder.exists() || !folder.isDirectory()) {
            return false;
        }

        //        File importFolder = new File(folder, internetArchiveID);
        //        if (!importFolder.exists() || !importFolder.isDirectory()) {
        //            System.err.println("Folder " + importFolder.getAbsolutePath() + " does not exist. abort");
        //            return false;
        //        }
        File importFolder = folder;
        String scandataFile = "";
        String marcFile = "";
        String abbyyFile = "";
        String jp2File = "";
        String[] filesInFolder = importFolder.list();
        for (String currentFile : filesInFolder) {
            if (currentFile.contains("scandata")) {
                scandataFile = currentFile;
            } else if (currentFile.contains("marc.xml")) {
                marcFile = currentFile;
            } else if (currentFile.contains(internetArchiveID + "_abbyy.gz")) {
                abbyyFile = currentFile;
            } else if (currentFile.contains(internetArchiveID + "_jp2.zip")) {
                jp2File = currentFile;
            }
        }

        if (scandataFile.equals("")) {
            System.err.println("could not find scandata file.");
            return false;
        }

        if (abbyyFile.equals("")) {
            System.err.println("could not find abbyy archive.");
            return false;
        }

        if (jp2File.equals("")) {
            System.err.println("could not find jp2 archive.");
            return false;
        }

        // if scandata is compressed, unzip scandata and set filename to xml file
        if (scandataFile.contains(".zip")) {
            logger.debug("Unzip scandata file");
            File zipfile = new File(importFolder, scandataFile);
            unzipFile(zipfile, importFolder);
            String[] filelist = importFolder.list();
            for (String currentFile : filelist) {
                if (currentFile.contains("scandata.xml")) {
                    scandataFile = currentFile;
                    break;
                }
            }
        }

        // unpack images
        if (jp2File.endsWith(".gz")) {
            File gzFile = new File(importFolder, jp2File);
            try {
                unGzip(gzFile, importFolder);
                jp2File = jp2File.replace(".gz", "");
            } catch (FileNotFoundException e) {
                logger.error(e);
                return false;
            } catch (IOException e) {
                logger.error(e);
                return false;
            }
        }
        if (jp2File.endsWith(".tar")) {
            File tarfile = new File(importFolder, jp2File);
            try {
                unTar(tarfile, importFolder);
            } catch (FileNotFoundException e) {
                logger.error(e);
                return false;
            } catch (IOException e) {
                logger.error(e);
                return false;
            } catch (ArchiveException e) {
                logger.error(e);
                return false;
            }
        }

        if (jp2File.endsWith(".zip")) {
            logger.debug("Unzip jp2 file");
            File zipfile = new File(importFolder, jp2File);
            unzipFile(zipfile, importFolder);
        }

        // copy files to import folder 
        File imageFolder = new File(importFolder.getAbsolutePath() + File.separator + internetArchiveID + "_jp2" + File.separator);
        if (!imageFolder.exists() || !imageFolder.isDirectory() || imageFolder.list().length == 0) {
            System.err.println("Image folder does not exist. Expected folder is " + imageFolder.getAbsolutePath());
            return false;
        }

        List<String> imagenameList = Arrays.asList(imageFolder.list());
        Collections.sort(imagenameList);

        logger.debug("import " + imagenameList.size() + " files.");
        File scandata = new File(importFolder.getAbsolutePath() + File.separator + scandataFile);
        List<ImageInformation> pages = readPageInformation(scandata, imagenameList);

        if (pages == null) {
            return false;
        }

        createPagination(null, monograph, ff.getDigitalDocument(), pages, prefs, 1);

        // find master folder
        File goobiImagesFolder = new File("/opt/digiverso/goobi/metadata/" + processid + "/images/");
        String[] subdirectories = goobiImagesFolder.list(DIRECTORY_FILTER);
        String masterFolderName = "";
        for (String dir : subdirectories) {
            if (dir.startsWith("master_") && dir.endsWith("_media")) {
                masterFolderName = dir;
                break;
            }
        }
        if (masterFolderName.equals("")) {
            System.err.println("Cannot find master folder for process " + processid);
            return false;
        }
        try {
            logger.debug("Import images to " + masterFolderName);
            File masterFolder = new File("/opt/digiverso/goobi/metadata/" + processid + "/images/" + masterFolderName + "/");
            for (int i = 0; i < pages.size(); i++) {
                ImageInformation ii = pages.get(i);
                if (ii.isAddToAccessFormats()) {
                    File currentImage = new File(imageFolder, ii.getImageName());
                    FileUtils.copyFileToDirectory(currentImage, masterFolder);
                }
            }

            //                FileUtils.copyDirectory(imageFolder, new File("/opt/digiverso/goobi/metadata/" + processid + "/images/" + masterFolderName + "/"));

            //            File scandataImportFile = new File(importFolder, scandataFile);
            //            File marcImportFile = new File(importFolder, marcFile);
            //            File abbyyImportFile = new File(importFolder, abbyyFile);
            //            File jp2ImportFile = new File(importFolder, jp2File);

            //            File dest = new File("/opt/digiverso/goobi/metadata/" + processid + "/images/source/");
            //            logger.debug("Import downloaded data to " + dest.getAbsolutePath());

            //            FileUtils.copyFileToDirectory(scandataImportFile, dest);
            //            FileUtils.copyFileToDirectory(marcImportFile, dest);
            //            FileUtils.copyFileToDirectory(abbyyImportFile, dest);
            //            FileUtils.copyFileToDirectory(jp2ImportFile, dest);

        } catch (IOException e) {
            logger.error(e);
        }

        // write mets file
        try {
            ff.write("/opt/digiverso/goobi/metadata/" + processid + "/meta.xml");
        } catch (WriteException e) {
            logger.error(e);
        }
        // delete import data

        System.gc();

        File processFile = new File(folder, processid + ".txt");
        logger.debug("try to delete " + processFile.getAbsolutePath());
        if (processFile.exists()) {
            FileUtils.deleteQuietly(processFile);
        }

        File issueFile = new File(folder, internetArchiveID + ".txt");
        logger.debug("try to delete " + issueFile.getAbsolutePath());
        if (issueFile.exists()) {
            FileUtils.deleteQuietly(issueFile);
        }
        //        logger.debug("try to delete " + importFolder.getAbsolutePath());
        //        if (importFolder.exists()) {
        //            try {
        //                FileUtils.deleteDirectory(importFolder);
        //            } catch (IOException e) {
        //                logger.error(e);
        //            }
        //        }

        return true;
    }

    private static boolean importJournal(Helper h, DocStruct periodical, Fileformat ff, Prefs prefs, String processid) throws PreferencesException {
        String foldername = h.getDownloadFolder();

        List<String> ids = h.getInternetArchiveIdentifier();

        logger.debug("import " + ids.size() + " downloaded parts.");

        File folder = new File(foldername);
        if (!folder.exists() || !folder.isDirectory()) {
            return false;
        }

        String[] downloadedFolder = folder.list(DIRECTORY_FILTER);
        List<String> downlaodedFolderList = Arrays.asList(downloadedFolder);

        for (String identifier : ids) {
            if (!downlaodedFolderList.contains(identifier)) {
                // current id was not downloaded, abort
                System.err.println("Id " + identifier + " was not downloaded, abort");
                return false;
            }
        }

        DocStruct periodicalVolume = periodical.getAllChildren().get(0);
        List<DocStruct> periodicalIssues = periodicalVolume.getAllChildren();

        MetadataType internetArchiveNameType = prefs.getMetadataTypeByName("InternetArchiveName");
        int ordernumber = 1;
        for (DocStruct issue : periodicalIssues) {
            Metadata md = issue.getAllMetadataByType(internetArchiveNameType).get(0);
            String currentName = md.getValue();

            logger.debug("import data for issue " + currentName);

            File importFolder = new File(foldername + currentName);
            if (!importFolder.exists() || !importFolder.isDirectory()) {
                System.err.println("Folder " + importFolder.getAbsolutePath() + " does not exist. abort");
                return false;
            }

            String scandataFile = "";
            String marcFile = "";
            String abbyyFile = "";
            String jp2File = "";
            String[] filesInFolder = importFolder.list();
            for (String currentFile : filesInFolder) {
                if (currentFile.contains("scandata")) {
                    scandataFile = currentFile;
                } else if (currentFile.contains("marc.xml")) {
                    marcFile = currentFile;
                } else if (currentFile.contains(currentName + "_abbyy.gz")) {
                    abbyyFile = currentFile;
                } else if (currentFile.contains(currentName + "_jp2.zip")) {
                    jp2File = currentFile;
                }
            }

            if (scandataFile.equals("")) {
                System.err.println("could not find scandata file.");
                return false;
            }

            if (abbyyFile.equals("")) {
                System.err.println("could not find abbyy archive.");
                return false;
            }

            if (jp2File.equals("")) {
                System.err.println("could not find jp2 archive.");
                return false;
            }

            // if scandata is compressed, unzip scandata and set filename to xml file
            if (scandataFile.contains(".zip")) {
                logger.debug("Unzip scandata file");
                File zipfile = new File(importFolder, scandataFile);
                unzipFile(zipfile, importFolder);
                String[] filelist = importFolder.list();
                for (String currentFile : filelist) {
                    if (currentFile.contains("scandata.xml")) {
                        scandataFile = currentFile;
                        break;
                    }
                }
            }

            // unpack images
            if (jp2File.endsWith(".gz")) {
                File gzFile = new File(importFolder, jp2File);
                try {
                    unGzip(gzFile, importFolder);
                    jp2File = jp2File.replace(".gz", "");
                } catch (FileNotFoundException e) {
                    logger.error(e);
                    return false;
                } catch (IOException e) {
                    logger.error(e);
                    return false;
                }
            }
            if (jp2File.endsWith(".tar")) {
                File tarfile = new File(importFolder, jp2File);
                try {
                    unTar(tarfile, importFolder);
                } catch (FileNotFoundException e) {
                    logger.error(e);
                    return false;
                } catch (IOException e) {
                    logger.error(e);
                    return false;
                } catch (ArchiveException e) {
                    logger.error(e);
                    return false;
                }
            }

            if (jp2File.endsWith(".zip")) {
                logger.debug("Unzip jp2 file");
                File zipfile = new File(importFolder, jp2File);
                unzipFile(zipfile, importFolder);
            }

            // copy files to import folder 
            File imageFolder = new File(importFolder.getAbsolutePath() + File.separator + currentName + "_jp2" + File.separator);
            if (!imageFolder.exists() || !imageFolder.isDirectory() || imageFolder.list().length == 0) {
                System.err.println("Image folder does not exist. Expected folder is " + imageFolder.getAbsolutePath());
                return false;
            }

            List<String> imagenameList = Arrays.asList(imageFolder.list());
            Collections.sort(imagenameList);

            logger.debug("import " + imagenameList.size() + " files.");
            File scandata = new File(importFolder.getAbsolutePath() + File.separator + scandataFile);
            List<ImageInformation> pages = readPageInformation(scandata, imagenameList);

            if (pages == null) {
                return false;
            }

            ordernumber = createPagination(periodicalVolume, issue, ff.getDigitalDocument(), pages, prefs, ordernumber);

            // find master folder
            File goobiImagesFolder = new File("/opt/digiverso/goobi/metadata/" + processid + "/images/");
            String[] subdirectories = goobiImagesFolder.list(DIRECTORY_FILTER);
            String masterFolderName = "";
            for (String dir : subdirectories) {
                if (dir.startsWith("master_") && dir.endsWith("_media")) {
                    masterFolderName = dir;
                    break;
                }
            }
            if (masterFolderName.equals("")) {
                System.err.println("Cannot find master folder for process " + processid);
                return false;
            }
            try {
                logger.debug("Import images to " + masterFolderName);
                File masterFolder = new File("/opt/digiverso/goobi/metadata/" + processid + "/images/" + masterFolderName + "/");
                for (int i = 0; i < pages.size(); i++) {
                    ImageInformation ii = pages.get(i);
                    if (ii.isAddToAccessFormats()) {
                        File currentImage = new File(imageFolder, ii.getImageName());
                        FileUtils.copyFileToDirectory(currentImage, masterFolder);
                    }
                }

                //                FileUtils.copyDirectory(imageFolder, new File("/opt/digiverso/goobi/metadata/" + processid + "/images/" + masterFolderName + "/"));

                File scandataImportFile = new File(importFolder, scandataFile);
                File marcImportFile = new File(importFolder, marcFile);
                File abbyyImportFile = new File(importFolder, abbyyFile);
                File jp2ImportFile = new File(importFolder, jp2File);

                File dest = new File("/opt/digiverso/goobi/metadata/" + processid + "/images/source/");
                logger.debug("Import downloaded data to " + dest.getAbsolutePath());

                FileUtils.copyFileToDirectory(scandataImportFile, dest);
                FileUtils.copyFileToDirectory(marcImportFile, dest);
                FileUtils.copyFileToDirectory(abbyyImportFile, dest);
                FileUtils.copyFileToDirectory(jp2ImportFile, dest);

            } catch (IOException e) {
                logger.error(e);
            }
        }

        // write mets file
        try {
            ff.write("/opt/digiverso/goobi/metadata/" + processid + "/meta.xml");
        } catch (WriteException e) {
            logger.error(e);
        }

        // delete import data

        System.gc();

        File processFile = new File(folder, processid + ".txt");
        logger.debug("try to delete " + processFile.getAbsolutePath());
        if (processFile.exists()) {
            FileUtils.deleteQuietly(processFile);
        }

        for (String currentName : ids) {
            File issueFile = new File(folder, currentName + ".txt");
            logger.debug("try to delete " + issueFile.getAbsolutePath());
            if (issueFile.exists()) {
                FileUtils.deleteQuietly(issueFile);
            }
            File importFolder = new File(folder, currentName);
            logger.debug("try to delete " + importFolder.getAbsolutePath());
            if (importFolder.exists()) {
                try {
                    FileUtils.deleteDirectory(importFolder);
                } catch (IOException e) {
                    logger.error(e);
                }
            }
        }
        return true;

    }

    private static int createPagination(DocStruct periodicalVolume, DocStruct issue, DigitalDocument digitalDocument, List<ImageInformation> pages,
            Prefs prefs, int ordernumber) {
        DocStructType dsTypePage = prefs.getDocStrctTypeByName("page");
        DocStruct physical = digitalDocument.getPhysicalDocStruct();

        for (ImageInformation image : pages) {
            if (image.isAddToAccessFormats()) {
                try {
                    DocStruct dsPage = digitalDocument.createDocStruct(dsTypePage);
                    physical.addChild(dsPage);
                    Metadata metaPhysPageNumber = new Metadata(prefs.getMetadataTypeByName("physPageNumber"));
                    dsPage.addMetadata(metaPhysPageNumber);
                    metaPhysPageNumber.setValue(String.valueOf(ordernumber++));
                    Metadata metaLogPageNumber = new Metadata(prefs.getMetadataTypeByName("logicalPageNumber"));
                    metaLogPageNumber.setValue(image.getLogicalNumber());
                    dsPage.addMetadata(metaLogPageNumber);
                    issue.addReferenceTo(dsPage, "logical_physical");
                    if (periodicalVolume != null) {
                        periodicalVolume.addReferenceTo(dsPage, "logical_physical");
                    }
                    dsPage.setImageName(image.getImageName());
                    logger.debug("create pagination for " + image);

                    if (image.getType() != null && !image.getType().isEmpty() && !image.getType().equalsIgnoreCase("Normal")) {
                        DocStructType dst = null;
                        if (image.getType().equalsIgnoreCase("contents")) {
                            dst = prefs.getDocStrctTypeByName("TableOfContents");
                        } else if (image.getType().equalsIgnoreCase("title")) {
                            dst = prefs.getDocStrctTypeByName("TitlePage");
                        } else if (image.getType().equalsIgnoreCase("cover")) {
                            dst = prefs.getDocStrctTypeByName("Cover");
                        } else if (image.getType().equalsIgnoreCase("preface")) {
                            dst = prefs.getDocStrctTypeByName("Preface");
                        } else if (image.getType().equalsIgnoreCase("index")) {
                            dst = prefs.getDocStrctTypeByName("Index");
                        } 

                        if (dst != null) {
                            logger.debug("Try to create sub docstruct for " + image.getType());
                            try {
                                DocStruct docStruct = digitalDocument.createDocStruct(dst);
                                issue.addChild(docStruct);
                                docStruct.addReferenceTo(dsPage, "logical_physical");

                            } catch (TypeNotAllowedForParentException e) {
                                logger.error(e);
                            } catch (TypeNotAllowedAsChildException e) {
                                logger.error(e);
                            }
                        }
                    }

                } catch (TypeNotAllowedAsChildException e) {
                    logger.error(e);
                } catch (TypeNotAllowedForParentException e) {
                    logger.error(e);
                } catch (MetadataTypeNotAllowedException e) {
                    logger.error(e);
                }
            }
        }
        return ordernumber;
    }

    @SuppressWarnings("unchecked")
    private static List<ImageInformation> readPageInformation(File scandata, List<String> imagenameList) {
        List<ImageInformation> answer = new ArrayList<ImageInformation>();
        Namespace archive = Namespace.getNamespace("http://archive.org/scribe/xml");
        SAXBuilder builder = new SAXBuilder(false);
        try {
            logger.debug("load scandata file " + scandata);
            Document document = builder.build(scandata);
            Element book = document.getRootElement();

            Element pageData = book.getChild("pageData");

            if (pageData == null) {
                pageData = book.getChild("pageData", archive);
            }
            List<Element> pageList = pageData.getChildren("page");
            if (pageList == null || pageList.isEmpty()) {
                pageList = pageData.getChildren("page", archive);
            }

            if (pageList.size() != imagenameList.size()) {
                System.err.println("Expected " + pageList.size() + " images in scandata file, but found " + imagenameList.size()
                        + " images in folder.");
                return null;

            }
            for (int i = 0; i < pageList.size(); i++) {
                //            for (Element page : pageList) {

                Element page = pageList.get(i);
                String imageName = imagenameList.get(i);
                String physicalNum = page.getAttributeValue("leafNum");

                Element access = page.getChild("addToAccessFormats");
                boolean addToAccessFormats;
                if (access == null) {
                    access = page.getChild("addToAccessFormats", archive);
                }
                if (access != null && access.getText() != null && access.getText().equals("false")) {
                    addToAccessFormats = false;
                } else {
                    addToAccessFormats = true;
                }

                String logical = "uncounted";
                String type = "";
                Element pageType = page.getChild("pageType");
                if (pageType == null) {
                    pageType = page.getChild("pageType", archive);
                }
                if (pageType != null && pageType.getText() != null) {
                    type = pageType.getText();
                }

                Element pageNumber = page.getChild("pageNumber");
                if (pageNumber == null) {
                    pageNumber = page.getChild("pageNumber", archive);
                }

                if (pageNumber != null && pageNumber.getText() != null) {
                    logical = pageNumber.getText();
                }

                ImageInformation ii = new ImageInformation(physicalNum, logical, imageName, type, addToAccessFormats);
                answer.add(ii);
            }

        } catch (JDOMException e) {
            logger.error(e);
        } catch (IOException e) {
            logger.error(e);
        }

        return answer;
    }

    private static List<File> unTar(final File inputFile, final File outputDir) throws FileNotFoundException, IOException, ArchiveException {

        logger.info(String.format("Untaring %s to dir %s.", inputFile.getAbsolutePath(), outputDir.getAbsolutePath()));

        final List<File> untaredFiles = new LinkedList<File>();
        final InputStream is = new FileInputStream(inputFile);
        final TarArchiveInputStream debInputStream = (TarArchiveInputStream) new ArchiveStreamFactory().createArchiveInputStream("tar", is);
        TarArchiveEntry entry = null;
        while ((entry = (TarArchiveEntry) debInputStream.getNextEntry()) != null) {
            final File outputFile = new File(outputDir, entry.getName());
            if (entry.isDirectory()) {
                logger.info(String.format("Attempting to write output directory %s.", outputFile.getAbsolutePath()));
                if (!outputFile.exists()) {
                    logger.info(String.format("Attempting to create output directory %s.", outputFile.getAbsolutePath()));
                    if (!outputFile.mkdirs()) {
                        throw new IllegalStateException(String.format("Couldn't create directory %s.", outputFile.getAbsolutePath()));
                    }
                }
            } else {
                logger.info(String.format("Creating output file %s.", outputFile.getAbsolutePath()));
                final OutputStream outputFileStream = new FileOutputStream(outputFile);
                IOUtils.copy(debInputStream, outputFileStream);
                outputFileStream.close();
            }
            untaredFiles.add(outputFile);
        }
        debInputStream.close();

        return untaredFiles;
    }

    private static File unGzip(final File inputFile, final File outputDir) throws FileNotFoundException, IOException {

        logger.info(String.format("Ungzipping %s to dir %s.", inputFile.getAbsolutePath(), outputDir.getAbsolutePath()));

        final File outputFile = new File(outputDir, inputFile.getName().substring(0, inputFile.getName().length() - 3));

        final GZIPInputStream in = new GZIPInputStream(new FileInputStream(inputFile));
        final FileOutputStream out = new FileOutputStream(outputFile);

        IOUtils.copy(in, out);

        in.close();
        out.close();

        return outputFile;
    }

    public static void unzipFile(File zipFile, File outputFolder) {

        byte[] buffer = new byte[1024];

        try {

            ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile));
            ZipEntry ze = zis.getNextEntry();

            while (ze != null) {

                String fileName = ze.getName();
                File newFile = new File(outputFolder + File.separator + fileName);

                logger.debug("file unzip : " + newFile.getAbsoluteFile());

                //create all non exists folders
                //else you will hit FileNotFoundException for compressed folder
                new File(newFile.getParent()).mkdirs();
                if (ze.isDirectory()) {
                    if (!newFile.exists()) {
                        newFile.mkdir();
                    }
                } else {
                    FileOutputStream fos = new FileOutputStream(newFile);

                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }

                    fos.close();
                }
                ze = zis.getNextEntry();
            }

            zis.closeEntry();
            zis.close();

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private static final FilenameFilter DIRECTORY_FILTER = DirectoryFileFilter.INSTANCE;

    private static boolean download(Helper helper) {
        String itemlist = helper.getFilename();
        BufferedReader in = null;
        List<String> lines = new ArrayList<String>();
        try {
            in = new BufferedReader(new FileReader(itemlist));
            String line = null;
            while ((line = in.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            logger.error(e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    logger.error(e);
                }
            }
        }

        for (String line : lines) {
            String filename = line.replace("http://archive.org/download/", "");

            String outputFilename = helper.getDownloadFolder() + File.separator + filename + ".txt";

            logger.debug("Download file list for entry " + line);
            downloadFile(line, outputFilename, helper);

            List<String> urls = new ArrayList<String>();
            try {
                in = new BufferedReader(new FileReader(helper.getDownloadFolder() + File.separator + filename + ".txt"));
                String bla = null;
                while ((bla = in.readLine()) != null) {
                    urls.add(bla);
                }
            } catch (IOException e) {
                logger.error(e);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        logger.error(e);
                    }
                }
            }

            String scandataPart = "";
            String marcPart = "";
            String abbyyPart = "";
            String jp2Part = "";

            String urlPart = "";

            for (String url : urls) {
                if (url.contains("<title>")) {
                    urlPart = url.substring(url.indexOf("<title>") + 15, url.indexOf("</title>")).trim();
                } else if (url.contains("scandata")) {
                    scandataPart = url.substring(9, url.indexOf("\">"));
                } else if (url.contains("marc.xml")) {
                    marcPart = url.substring(9, url.indexOf("\">"));
                } else if (url.contains(filename + "_abbyy.gz")) {
                    abbyyPart = url.substring(9, url.indexOf("\">"));
                } else if (url.contains(filename + "_jp2.zip")) {
                    jp2Part = url.substring(9, url.indexOf("\">"));
                }
            }

            File downloadFolder = new File(helper.getDownloadFolder() + filename);
            if (!downloadFolder.exists()) {
                downloadFolder.mkdir();
            }
            logger.debug("Download scandata file " + scandataPart);
            if (scandataPart.contains(filename)) {
                downloadFile("http://archive.org" + urlPart + scandataPart, helper.getDownloadFolder() + filename + File.separator + scandataPart,
                        helper);
            } else {
                downloadFile("http://archive.org" + urlPart + scandataPart, helper.getDownloadFolder() + filename + File.separator + filename + "_"
                        + scandataPart, helper);

            }
            logger.debug("Download marc file " + marcPart);
            downloadFile("http://archive.org" + urlPart + marcPart, helper.getDownloadFolder() + filename + File.separator + marcPart, helper);

            logger.debug("Download abbyy file " + abbyyPart);
            downloadFile("http://archive.org" + urlPart + abbyyPart, helper.getDownloadFolder() + filename + File.separator + abbyyPart, helper);

            logger.debug("Download jp2 file " + jp2Part);
            downloadFile("http://archive.org" + urlPart + jp2Part, helper.getDownloadFolder() + filename + File.separator + jp2Part, helper);

        }
        return true;
    }

    private static void downloadFile(String line, String outputFilename, Helper h) {
        if (!useProxy) {
            downloadFileWithoutProxy(line, outputFilename);
        } else {

            HttpHost proxy = new HttpHost(h.getProxyUrl(), h.getProxyPort());
            boolean useProxy = true;
            DefaultHttpClient httpclient = null;

            HttpGet method = new HttpGet(line);
            InputStream istr = null;
            OutputStream ostr = null;
            try {
                httpclient = new DefaultHttpClient();
                if (useProxy) {
                    httpclient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);
                }
                byte[] response = httpclient.execute(method, responseHandler);

                istr = new ByteArrayInputStream(response);
                ostr = new FileOutputStream(outputFilename);

                // Transfer bytes from in to out
                byte[] buf = new byte[1024];
                int len;
                while ((len = istr.read(buf)) > 0) {
                    ostr.write(buf, 0, len);
                }
            } catch (Exception e) {
                logger.error("Unable to connect to url " + line, e);
            } finally {
                method.releaseConnection();
                if (httpclient != null) {
                    httpclient.close();
                }
                if (istr != null) {
                    try {
                        istr.close();
                    } catch (IOException e) {
                        logger.error(e);
                    }
                }
                if (ostr != null) {
                    try {
                        ostr.close();
                    } catch (IOException e) {
                        logger.error(e);
                    }
                }
            }
        }
    }

    static ResponseHandler<byte[]> responseHandler = new ResponseHandler<byte[]>() {
        @Override
        public byte[] handleResponse(HttpResponse response) throws ClientProtocolException, IOException {
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                return EntityUtils.toByteArray(entity);
            } else {
                return null;
            }
        }
    };

    private static void downloadFileWithoutProxy(String line, String outputFilename) {
        HttpClient client = new DefaultHttpClient();
        HttpGet method = new HttpGet(line);
        InputStream istr = null;
        OutputStream ostr = null;
        try {
            byte[] response = client.execute(method, responseHandler);
            istr = new ByteArrayInputStream(response);
            ostr = new FileOutputStream(outputFilename);

            // Transfer bytes from in to out
            byte[] buf = new byte[1024];
            int len;
            while ((len = istr.read(buf)) > 0) {
                ostr.write(buf, 0, len);
            }

        } catch (IOException e) {
            logger.error(e);
        } finally {
            method.releaseConnection();
            if (istr != null) {
                try {
                    istr.close();
                } catch (IOException e) {
                    logger.error(e);
                }
            }
            if (ostr != null) {
                try {
                    ostr.close();
                } catch (IOException e) {
                    logger.error(e);
                }
            }
        }
    }

    @SuppressWarnings("unused")
    private static boolean downloadWithWget(Helper helper) {
        InputStream is = null;
        InputStream es = null;
        OutputStream out = null;

        String itemlist = helper.getFilename();
        String downloadFolder = helper.getDownloadFolder();

        ProcessBuilder pb = new ProcessBuilder("wget", "-r", "-H", "-nc", "-np", "-nH", "--cut-dirs=2", "-e", "robots=off", "-l1", "-i", itemlist);
        pb.directory(new File(downloadFolder));

        try {
            boolean errorsExist = false;

            Process process = pb.start();

            int rueckgabe = process.waitFor();
            is = process.getInputStream();
            es = process.getErrorStream();
            out = process.getOutputStream();
            Scanner scanner = new Scanner(is);
            while (scanner.hasNextLine()) {
                String myLine = scanner.nextLine();
                System.out.println(myLine);
            }

            scanner.close();
            scanner = new Scanner(es);
            while (scanner.hasNextLine()) {
                errorsExist = true;
                System.err.println(scanner.nextLine());
            }
            scanner.close();

            if (errorsExist) {
                return false;
            } else {
                return true;
            }
        } catch (IOException e) {
            logger.error(e);
            return false;
        } catch (InterruptedException e) {
            logger.error(e);
            return false;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    is = null;
                }
            }
            if (es != null) {
                try {
                    es.close();
                } catch (IOException e) {
                    es = null;
                }

            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    out = null;
                }
            }
        }
    }

    private static boolean writeIdentifier(Helper helper, String name) {
        List<String> values = new ArrayList<String>();
        if (name == null || name.isEmpty()) {
            values = helper.getInternetArchiveIdentifier();
        } else {
            values.add(name);
        }

        if (values != null && !values.isEmpty()) {
            logger.debug("Adding " + values.size() + " targets to download list.");
            String filename = helper.getFilename();
            FileWriter fw = null;
            BufferedWriter out = null;
            try {
                fw = new FileWriter(filename);
                out = new BufferedWriter(fw);
                for (String value : values) {
                    logger.debug("added value " + value + " to download list.");
                    out.write("http://archive.org/download/" + value);
                    out.newLine();
                }

            } catch (IOException e) {
                logger.error(e);
                return false;
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e) {
                        logger.error(e);
                        return false;
                    }
                }
                if (fw != null) {
                    try {
                        fw.close();
                    } catch (IOException e) {
                        logger.error(e);
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static Options generateOptions() {
        Options options = new Options();

        Option operation = new Option("o", "operation", true, "operation - allowed values are 'download', 'downloadbook' and 'import' ");
        operation.setArgs(1);
        operation.setArgName("operation");
        operation.setRequired(true);
        options.addOption(operation);

        Option id = new Option("i", "id", true, "process id");
        id.setArgs(1);
        id.setArgName("id");
        id.setRequired(true);
        options.addOption(id);

        Option ruleset = new Option("r", "ruleset", false, "ruleset file");
        ruleset.setArgs(1);
        ruleset.setArgName("ruleset");
        ruleset.setRequired(false);
        options.addOption(ruleset);

        Option properties = new Option("c", "config", false, "configuration file");
        properties.setArgs(1);
        properties.setArgName("config");
        properties.setRequired(false);
        options.addOption(properties);

        Option name = new Option("n", "name", false, "internet archive identifier for books (b-number)");
        name.setArgs(1);
        name.setArgName("name");
        name.setRequired(false);
        options.addOption(name);

        options.addOption(new Option("h", "help", false, "help"));

        return options;
    }
}
