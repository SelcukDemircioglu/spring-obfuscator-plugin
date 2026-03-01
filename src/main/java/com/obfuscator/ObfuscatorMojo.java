package com.obfuscator;

import com.obfuscator.config.ObfuscationConfig;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Mojo(name = "obfuscate", defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class ObfuscatorMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "obfuscation.level", defaultValue = "LEVEL_1_BASIC")
    private ObfuscationLevel level;

    @Parameter(property = "obfuscation.enabled", defaultValue = "true")
    private boolean enabled;

    @Parameter(property = "obfuscation.excludePackages")
    private String[] excludePackages;

    @Parameter(property = "obfuscation.preserveSpringBeans", defaultValue = "true")
    private boolean preserveSpringBeans;

    /**
     * LEVEL_4_ENCRYPTED only — optional.
     * 64-character hex string (= 32 bytes) used as the AES-256 key.
     * If omitted, a cryptographically random key is generated per build and
     * stored at META-INF/obf/.key inside the compiled classes.
     */
    @Parameter(property = "obfuscation.encryptionKey")
    private String encryptionKey;

    /**
     * LEVEL_4_ENCRYPTED only — recommended.
     * Binary name of the @SpringBootApplication main class,
     * e.g. "com.example.MyApplication".
     * When packaging the fat-JAR, set spring-boot-maven-plugin's
     * &lt;mainClass&gt; to "com.obfuscator.runtime.EncryptedLauncher" and
     * this value is stored in META-INF/obf/.mainclass so it is found at runtime.
     */
    @Parameter(property = "obfuscation.mainClass")
    private String mainClass;

    /**
     * Tüm uygulama sınıflarını tek bir pakette topla (paket düzleştirme).
     * <p>true ise obfuscation tamamlandıktan sonra tüm .class dosyaları
     * {@link #flattenTargetPackage} altına taşınır ve bytecode referansları
     * ASM ClassRemapper ile güncellenir.
     * <p>false (default) ise paket yapısı değiştirilmez.
     */
    @Parameter(property = "obfuscation.flattenPackages", defaultValue = "false")
    private boolean flattenPackages;

    /**
     * Paket düzleştirmesinde kullanılacak hedef paket.
     * Örn: "tr.sesasis.app"  — boş bırakılsa kök pakete taşınır.
     * {@link #flattenPackages} = true olduğunda geçerlidir.
     */
    @Parameter(property = "obfuscation.flattenTargetPackage", defaultValue = "")
    private String flattenTargetPackage;

    /**
     * Düzleştirilen sınıfların dosya adları da anlamsızlaştırılsın mı?
     * true ise her taşınan sınıf a, b, c, … şeklinde kısa isim alır.
     * false (default) ise orijinal basit ad korunur.
     * {@link #flattenPackages} = true olduğunda geçerlidir.
     */
    @Parameter(property = "obfuscation.flattenObfuscateNames", defaultValue = "false")
    private boolean flattenObfuscateNames;

    /**
     * LEVEL_2 string şifreleme için AES anahtarı.
     * Tam olarak 16 karakter olmalıdır (AES-128).
     * Belirtilmezse varsayılan sabit anahtar kullanılır.
     * Maven property: -Dobfuscation.stringEncryptionKey=MySecretKey1234
     */
    @Parameter(property = "obfuscation.stringEncryptionKey")
    private String stringEncryptionKey;

    @Override
    public void execute() throws MojoExecutionException {
        if (!enabled) {
            getLog().info("Obfuscation devre disi, atliyor...");
            return;
        }

        String outputDirectory = project.getBuild().getOutputDirectory();
        File   classesDir      = new File(outputDirectory);

        if (!classesDir.exists()) {
            getLog().warn("Classes dizini bulunamadi: " + outputDirectory);
            return;
        }

        ObfuscationConfig config = new ObfuscationConfig();
        config.setLevel(level);
        config.setPreserveSpringBeans(preserveSpringBeans);
        config.setExcludePackages(excludePackages);
        config.setEncryptionKey(encryptionKey);
        config.setMainClass(mainClass);
        config.setFlattenPackages(flattenPackages);
        config.setFlattenTargetPackage(flattenTargetPackage);
        config.setFlattenObfuscateNames(flattenObfuscateNames);
        config.setStringEncryptionKey(stringEncryptionKey);

        ObfuscatorEngine engine = new ObfuscatorEngine(
            new ObfuscatorEngine.PluginLogger() {
                @Override public void info(String msg)  { getLog().info(msg);  }
                @Override public void warn(String msg)  { getLog().warn(msg);  }
                @Override public void error(String msg, Throwable t) { getLog().error(msg, t); }
            },
            config, mainClass, encryptionKey
        );

        try {
            engine.execute(classesDir);
            sanitizeProjectPom();
        } catch (Exception e) {
            throw new MojoExecutionException("Obfuscation sirasinda hata olustu", e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * JAR içine gömülecek pom.xml'den com.obfuscator plugin ve boş kalan
     * profile/build/plugins elementlerini kaldırır.
     *
     * <p>Maven'ın maven-archiver'ı, jar oluştururken {@code project.getFile()}'dan
     * pom.xml okur. Bu metod temizlenmiş bir kopyayı {@code target/sanitized-pom.xml}
     * olarak yazar ve {@code project.setFile()} ile Maven'ın bu kopyayı kullanmasını sağlar.
     * Böylece obfuscated jar içinde obfuscator konfigürasyonu görünmez.
     */
    private void sanitizeProjectPom() {
        try {
            File originalPom = project.getFile();
            if (originalPom == null || !originalPom.exists()) {
                getLog().debug("[POM-SANITIZE] pom.xml bulunamadı — atlanıyor.");
                return;
            }

            byte[] pomBytes = Files.readAllBytes(originalPom.toPath());

            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            // XXE koruması
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = dbf.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(pomBytes));

            // 1) com.obfuscator i&#231;eren t&#252;m &lt;profile&gt; nodlar&#305;n&#305; b&#252;t&#252;n&#252;yle kald&#305;r
            removeObfuscatorProfiles(doc);

            // 2) &lt;plugin&gt; d&#252;zeyinde kalan ba&#351;ka com.obfuscator referanslar&#305;n&#305; kald&#305;r
            removeObfuscatorPluginNodes(doc);

            // 3) Bo&#351; kalan container elementlerini temizle
            removeEmptyContainerNodes(doc, "plugins");
            removeEmptyContainerNodes(doc, "build");
            removeEmptyContainerNodes(doc, "profiles");

            // 3) Serialize
            Transformer tf = TransformerFactory.newInstance().newTransformer();
            tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            tf.setOutputProperty(OutputKeys.INDENT, "yes");
            tf.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
            StringWriter sw = new StringWriter();
            tf.transform(new DOMSource(doc), new StreamResult(sw));

            // 4) target/sanitized-pom.xml'e yaz + Maven'a bildir
            File sanitizedPom = new File(project.getBuild().getDirectory(), "sanitized-pom.xml");
            sanitizedPom.getParentFile().mkdirs();
            Files.writeString(sanitizedPom.toPath(), sw.toString());

            project.setFile(sanitizedPom);
            getLog().info("[POM-SANITIZE] Embedded pom.xml'den com.obfuscator referanslari temizlendi.");

        } catch (Exception e) {
            getLog().warn("[POM-SANITIZE] pom.xml temizleme atlanıyor: " + e.getMessage());
        }
    }

    /**
     * com.obfuscator groupId'li plugin içeren tüm &lt;profile&gt; nodlarını
     * (id, activation, build, properties dahil) bütünüyle kaldırır.
     */
    private void removeObfuscatorProfiles(Document doc) {
        NodeList profileNodes = doc.getElementsByTagName("profile");
        List<Node> toRemove = new ArrayList<>();

        for (int i = 0; i < profileNodes.getLength(); i++) {
            Node profile = profileNodes.item(i);
            if (profile instanceof org.w3c.dom.Element
                    && profileContainsObfuscator((org.w3c.dom.Element) profile)) {
                toRemove.add(profile);
            }
        }

        for (Node node : toRemove) {
            Node parent = node.getParentNode();
            if (parent != null) {
                Node prev = node.getPreviousSibling();
                if (prev != null && prev.getNodeType() == Node.TEXT_NODE
                        && prev.getNodeValue().isBlank()) {
                    parent.removeChild(prev);
                }
                parent.removeChild(node);
            }
        }

        if (!toRemove.isEmpty()) {
            getLog().info("[POM-SANITIZE] " + toRemove.size()
                + " com.obfuscator profili kaldırıldı.");
        }
    }

    /** Profile altındaki herhangi bir &lt;plugin&gt;'in groupId'si com.obfuscator mu? */
    private boolean profileContainsObfuscator(org.w3c.dom.Element profile) {
        NodeList plugins = profile.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            NodeList children = plugins.item(i).getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if ("groupId".equals(child.getNodeName())
                        && "com.obfuscator".equals(child.getTextContent().trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * DOM ağacında groupId = com.obfuscator olan tüm &lt;plugin&gt; düğümlerini kaldırır.
     */
    private void removeObfuscatorPluginNodes(Document doc) {
        NodeList pluginNodes = doc.getElementsByTagName("plugin");
        List<Node> toRemove = new ArrayList<>();

        for (int i = 0; i < pluginNodes.getLength(); i++) {
            Node plugin = pluginNodes.item(i);
            NodeList children = plugin.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if ("groupId".equals(child.getNodeName())
                        && "com.obfuscator".equals(child.getTextContent().trim())) {
                    toRemove.add(plugin);
                    break;
                }
            }
        }

        for (Node node : toRemove) {
            Node parent = node.getParentNode();
            if (parent != null) {
                // satır sonu whitespace text düğümünü de kaldır
                Node prev = node.getPreviousSibling();
                if (prev != null && prev.getNodeType() == Node.TEXT_NODE
                        && prev.getNodeValue().isBlank()) {
                    parent.removeChild(prev);
                }
                parent.removeChild(node);
            }
        }
    }

    /**
     * Verilen tag adındaki elementleri: sadece whitespace içeriyorsa (gerçek alt element yok)
     * DOM'dan kaldırır.
     */
    private void removeEmptyContainerNodes(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        List<Node> toRemove = new ArrayList<>();

        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            boolean hasElementChild = false;
            NodeList children = node.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                if (children.item(j).getNodeType() == Node.ELEMENT_NODE) {
                    hasElementChild = true;
                    break;
                }
            }
            if (!hasElementChild) {
                toRemove.add(node);
            }
        }

        for (Node node : toRemove) {
            Node parent = node.getParentNode();
            if (parent != null) {
                Node prev = node.getPreviousSibling();
                if (prev != null && prev.getNodeType() == Node.TEXT_NODE
                        && prev.getNodeValue().isBlank()) {
                    parent.removeChild(prev);
                }
                parent.removeChild(node);
            }
        }
    }

}
