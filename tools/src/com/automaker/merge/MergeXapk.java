package com.automaker.merge;

import com.reandroid.apk.ApkBundle;
import com.reandroid.apk.ApkModule;
import com.reandroid.app.AndroidManifest;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class MergeXapk {
    private static final String[] META_NAMES = {
        "com.android.vending.splits.required",
        "com.android.vending.splits",
        "com.android.stamp.source",
        "com.android.stamp.type",
        "com.android.vending.derived.apk.id",
    };

    private MergeXapk() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: MergeXapk <input.xapk> <output.apk>");
        }
        File input = new File(args[0]);
        File output = new File(args[1]);
        File parent = output.getParentFile();
        if (parent != null) parent.mkdirs();
        File modules = new File(parent, "modules");
        deleteTree(modules);
        modules.mkdirs();
        extractApks(input, modules);
        try (ApkBundle bundle = new ApkBundle()) {
            bundle.loadApkDirectory(modules);
            try (ApkModule merged = bundle.mergeModules()) {
                stripSplitMetadata(merged);
                merged.setApkSignatureBlock(null);
                if (output.exists() && !output.delete()) {
                    throw new IllegalStateException("The merged APK could not be replaced.");
                }
                merged.writeApk(output);
            }
        } finally {
            deleteTree(modules);
        }
    }

    private static void extractApks(File xapk, File dest) throws Exception {
        int index = 0;
        try (ZipFile zip = new ZipFile(xapk)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !name.toLowerCase().endsWith(".apk")) continue;
                if (name.startsWith("/") || name.contains("../") || name.contains("\0")) {
                    throw new IllegalArgumentException("The XAPK contains an unsafe path.");
                }
                String base = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
                File target = new File(dest, index + "_" + base);
                if (!target.getCanonicalPath().startsWith(dest.getCanonicalPath() + File.separator)) {
                    throw new IllegalArgumentException("The XAPK contains an unsafe path.");
                }
                try (InputStream input = zip.getInputStream(entry); FileOutputStream output = new FileOutputStream(target)) {
                    input.transferTo(output);
                }
                index++;
            }
        }
        if (index == 0) throw new IllegalArgumentException("The XAPK does not contain an APK.");
    }

    private static void stripSplitMetadata(ApkModule apk) {
        AndroidManifestBlock manifest = apk.getAndroidManifest();
        if (manifest == null || manifest.getManifestElement() == null) return;
        ResXmlElement root = manifest.getManifestElement();
        ResXmlElement application = manifest.getApplicationElement();
        removeSplitAttributes(root);
        if (application != null) removeSplitAttributes(application);
        removeNamedChildren(root, "uses-split");
        for (String name : META_NAMES) removeMeta(manifest, name);
        manifest.refresh();
    }

    private static void removeSplitAttributes(ResXmlElement element) {
        removeAttribute(element, AndroidManifest.ID_requiredSplitTypes, AndroidManifest.NAME_requiredSplitTypes);
        removeAttribute(element, AndroidManifest.ID_splitTypes, AndroidManifest.NAME_splitTypes);
        removeAttribute(element, AndroidManifest.ID_isSplitRequired, AndroidManifest.NAME_isSplitRequired);
        removeAttribute(element, AndroidManifest.ID_extractNativeLibs, AndroidManifest.NAME_extractNativeLibs);
        ResXmlAttribute split = element.searchAttributeByName(AndroidManifest.NAME_split);
        if (split != null) element.removeAttribute(split);
    }

    private static void removeAttribute(ResXmlElement element, int id, String name) {
        ResXmlAttribute attribute = element.searchAttributeByResourceId(id);
        if (attribute == null) attribute = element.searchAttributeByName(name);
        if (attribute != null) element.removeAttribute(attribute);
    }

    private static void removeNamedChildren(ResXmlElement parent, String tag) {
        List<ResXmlElement> found = new ArrayList<>();
        var iterator = parent.getElements(tag);
        while (iterator.hasNext()) {
            Object next = iterator.next();
            if (next instanceof ResXmlElement element) found.add(element);
        }
        for (ResXmlElement element : found) {
            ResXmlElement owner = element.getParentElement();
            if (owner != null) owner.remove(element);
        }
    }

    private static void removeMeta(AndroidManifestBlock manifest, String name) {
        List<ResXmlElement> found = new ArrayList<>();
        var iterator = manifest.recursiveElements();
        while (iterator.hasNext()) {
            Object next = iterator.next();
            if (!(next instanceof ResXmlElement element)) continue;
            if (element.equalsName(AndroidManifest.TAG_meta_data)
                && name.equals(AndroidManifestBlock.getAndroidNameValue(element))) {
                found.add(element);
            }
        }
        for (ResXmlElement element : found) {
            ResXmlElement owner = element.getParentElement();
            if (owner != null) owner.remove(element);
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}
