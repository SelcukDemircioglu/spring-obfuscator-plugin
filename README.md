# Spring Boot Obfuscator Maven Plugin

> **Spring Boot 3+ uygulamaları için ASM tabanlı, dört kademeli obfuscation Maven eklentisi.**

---

## İçindekiler

- [Spring Boot Obfuscator Maven Plugin](#spring-boot-obfuscator-maven-plugin)
  - [İçindekiler](#i̇çindekiler)
  - [Özellikler](#özellikler)
  - [Gereksinimler](#gereksinimler)
  - [Kurulum](#kurulum)
    - [1 — Plugin JAR'ını Yerel `.m2`'ye yükle (geliştirme)](#1--plugin-jarını-yerel-m2ye-yükle-geliştirme)
    - [2 — Nexus Maven Repo'ya Deploy (CI/üretim)](#2--nexus-maven-repoya-deploy-ciüretim)
  - [Hızlı Başlangıç](#hızlı-başlangıç)
  - [Obfuscation Seviyeleri](#obfuscation-seviyeleri)
    - [LEVEL\_1\_BASIC](#level_1_basic)
    - [LEVEL\_2\_MEDIUM](#level_2_medium)
    - [LEVEL\_3\_ADVANCED](#level_3_advanced)
    - [LEVEL\_4\_ENCRYPTED](#level_4_encrypted)
  - [Spring Bean Koruması](#spring-bean-koruması)
  - [Paket Düzleştirme](#paket-düzleştirme)
  - [LEVEL\_4 — AES-256-GCM Sınıf Şifreleme](#level_4--aes-256-gcm-sınıf-şifreleme)
    - [Şema](#şema)
    - [Yapılandırma](#yapılandırma)
    - [Sabit Anahtar Kullanımı (CI)](#sabit-anahtar-kullanımı-ci)
  - [Yapılandırma Referansı](#yapılandırma-referansı)
  - [pom.xml Profil Örnekleri](#pomxml-profil-örnekleri)
    - [Lokal Geliştirme — LEVEL\_3](#lokal-geliştirme--level_3)
    - [Üretim — LEVEL\_4 + Paket Düzleştirme](#üretim--level_4--paket-düzleştirme)
  - [Docker / CI Entegrasyonu](#docker--ci-entegrasyonu)
    - [Dockerfile](#dockerfile)
    - [Build Komutu](#build-komutu)
    - [Lokal Geliştirici Scripti](#lokal-geliştirici-scripti)
    - [Jenkins Pipeline](#jenkins-pipeline)
  - [Nexus'a Deploy](#nexusa-deploy)
  - [Sık Sorulan Sorular](#sık-sorulan-sorular)
  - [Proje Yapısı](#proje-yapısı)
  - [Lisans](#lisans)

---

## Özellikler

| Özellik | Açıklama |
|---------|----------|
| **4 Kademe** | LEVEL_1 → LEVEL_4, kademeli güçlenen koruma |
| **Spring Uyumlu** | Entity, Configuration, Service, Controller vs. otomatik tanınır; yanlış renaming olmaz |
| **ASM 9.6** | Bytecode doğrudan işlenir, kaynak kodu gerekmez |
| **AES-256-GCM** | LEVEL_4'te sınıf bytecode'ları şifrelenir; anahtar build-time'da üretilir veya enjekte edilir |
| **Paket Düzleştirme** | Tüm sınıfları tek pakette toplar, decompiler analizini güçleştirir |
| **BuildKit Desteği** | Dockerfile secret mount ile anahtar hiçbir layer'a yazılmaz |
| **Maven Parametrik** | Her parametre `-D` ile build ortamından ezilebilir |

---

## Gereksinimler

- Java 17+
- Maven 3.8+
- Spring Boot 3.x

---

## Kurulum

### 1 — Plugin JAR'ını Yerel `.m2`'ye yükle (geliştirme)

```bash
git clone https://github.com/your-org/spring-obfuscator-plugin.git
cd spring-obfuscator-plugin
mvn clean install
```

### 2 — Nexus Maven Repo'ya Deploy (CI/üretim)

```bash
bash scripts/deploy-plugin-to-nexus.sh
```

Script `~/.m2/settings.xml` içindeki `nexus-releases` server kimlik bilgilerini kullanır.

---

## Hızlı Başlangıç

`pom.xml` profiline ekleyin:

```xml
<profiles>
  <profile>
    <id>obfuscate</id>
    <build>
      <plugins>
        <plugin>
          <groupId>com.obfuscator</groupId>
          <artifactId>spring-obfuscator-maven-plugin</artifactId>
          <version>1.0.0</version>
          <executions>
            <execution>
              <id>obfuscate</id>
              <phase>process-classes</phase>
              <goals><goal>obfuscate</goal></goals>
            </execution>
          </executions>
          <configuration>
            <level>LEVEL_3_ADVANCED</level>
            <preserveSpringBeans>true</preserveSpringBeans>
          </configuration>
        </plugin>
      </plugins>
    </build>
  </profile>
</profiles>
```

Build çalıştırın:

```bash
mvn clean package -P obfuscate
```

---

## Obfuscation Seviyeleri

### LEVEL_1_BASIC

**Ne yapar:**
- `private` alan adlarını rastgele kısa adlarla değiştirir (`a`, `b`, `aa`, …)
- `private` metot adlarını yeniden adlandırır
- Yerel değişken adlarını siler
- Tüm `GETFIELD` / `PUTFIELD` / `INVOKEVIRTUAL` vb. çağrı noktalarını günceller
- Lambda ve metot referanslarının (`this::method`) bootstrap `Handle` nesnelerini remap eder

**Hedef:** Decompiler çıktısındaki anlam kaynaklı adları ortadan kaldırır.

---

### LEVEL_2_MEDIUM

**LEVEL_1 + şunları ekler:**
- String literal'leri XOR tabanlı runtime şifreleme ile saklar
- Decompiler dizge aramalarını engeller

---

### LEVEL_3_ADVANCED

**LEVEL_2 + şunları ekler:**
- Kontrol akışı karmaşıklaştırma (opaque predicate ekleme)
- Ölü kod ekleme
- `GOTO` reshuffling

**Hedef:** Statik analiz araçlarını ve decompiler'ları yanıltır.

---

### LEVEL_4_ENCRYPTED

**LEVEL_3 + şunları ekler:**
- `PARTIAL` korumalı sınıfların bytecode'ları **AES-256-GCM** ile şifrelenir
- Şifreli dosyalar JAR içinde `META-INF/obf/<ClassName>.enc` olarak saklanır
- `EncryptedClassLoader` otomatik olarak JAR'a enjekte edilir
- `EncryptedLauncher` Spring Boot `Start-Class` olarak ayarlanır; başlangıçta gerçek main class'ı çağırır

> **Not:** `@Entity`, `@Configuration` sınıfları şifrelenmez — Spring/JPA ya da Hibernate bunları yansıma ile yükler.

---

## Spring Bean Koruması

`preserveSpringBeans=true` (varsayılan) olduğunda plugin sınıfları otomatik tarar:

| Annotasyon | Koruma Seviyesi | Davranış |
|------------|-----------------|----------|
| `@Entity`, `@Table`, `@MappedSuperclass` | **FULL** | Sınıf tamamen atlanır |
| `@SpringBootApplication`, `@Configuration`, `@Bean` | **FULL** | Sınıf tamamen atlanır |
| `@Service`, `@Repository`, `@Controller`, `@RestController`, `@Component` | **PARTIAL** | Yalnızca `private` alanlar, yardımcı metotlar ve string literal'ler işlenir; public API dokunulmaz |
| (annotasyon yok) | **NONE** | Tam obfuscation pipeline uygulanır |

`preserveSpringBeans=false` yapılırsa tüm sınıflar NONE olarak değerlendirilir — **üretimde önerilmez.**

---

## Paket Düzleştirme

`flattenPackages=true` ile tüm sınıflar tek bir hedef pakete taşınır. Decompiler'ın paket ağacından anlam çıkarması engellenir.

```xml
<configuration>
  <level>LEVEL_3_ADVANCED</level>
  <preserveSpringBeans>true</preserveSpringBeans>

  <!-- Paket düzleştirme -->
  <flattenPackages>true</flattenPackages>
  <flattenTargetPackage>tr.sesasis.app</flattenTargetPackage>
</configuration>
```

**Nasıl Çalışır:**

1. Tüm `.class` dosyaları için `old → new` ad eşlemesi oluşturulur
2. ASM `ClassRemapper` ile her `.class` içindeki tüm referanslar güncellenir
3. Dosyalar hedef pakete taşınır
4. Boş dizinler temizlenir
5. İç sınıflar (`Outer$Inner`) dış sınıfıyla birlikte tutulur
6. Ad çakışması olursa `MyClass1`, `MyClass2`, … şeklinde son ek eklenir
7. `META-INF/` hiçbir zaman değiştirilmez

**Parametreler:**

| Parametre | Tür | Varsayılan | Açıklama |
|-----------|-----|------------|----------|
| `flattenPackages` | `boolean` | `false` | Etkinleştir/devre dışı bırak |
| `flattenTargetPackage` | `String` | `""` (kök paket) | Hedef paket (nokta notasyonu) |

---

## LEVEL_4 — AES-256-GCM Sınıf Şifreleme

### Şema

```
Build zamanı:
  Bytecode (obfuscated)
    ↓ AES-256-GCM (12-byte IV + auth tag)
  META-INF/obf/ClassName.enc
  META-INF/obf/.key         (32-byte raw AES key)
  META-INF/obf/.classes     (şifreli sınıf listesi)
  META-INF/obf/.mainclass   (gerçek main class adı)

Çalışma zamanı:
  JVM → EncryptedLauncher.main()
    → AES key okunur
    → EncryptedClassLoader yüklenir (thread context ClassLoader)
    → Gerçek main class reflection ile çağrılır
    → EncryptedClassLoader.findClass() → .enc dosyası AES ile çözülür → defineClass()
```

### Yapılandırma

```xml
<configuration>
  <level>LEVEL_4_ENCRYPTED</level>
  <preserveSpringBeans>true</preserveSpringBeans>
  <mainClass>com.example.MyApplication</mainClass>
  <!-- encryptionKey belirtilmezse her build'de rastgele 32-byte anahtar üretilir -->
  <!-- <encryptionKey>6f8bXXXX...64char-hex-string...XXXX</encryptionKey> -->
</configuration>

<!-- Spring Boot fat-JAR Start-Class'ını EncryptedLauncher olarak ayarla -->
<plugin>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-maven-plugin</artifactId>
  <configuration>
    <mainClass>com.obfuscator.runtime.EncryptedLauncher</mainClass>
  </configuration>
</plugin>
```

### Sabit Anahtar Kullanımı (CI)

Anahtarı build ortamından geçirmek için:

```bash
# AES-256 anahtar üret (bir kez)
openssl rand -hex 32

# Maven parametresi olarak geç
mvn package -P obfuscate -Dobfuscation.encryptionKey=<64-char-hex>
```

---

## Yapılandırma Referansı

| Parametre | Tür | Varsayılan | Açıklama |
|-----------|-----|------------|----------|
| `level` | `ObfuscationLevel` | `LEVEL_1_BASIC` | Obfuscation seviyesi |
| `enabled` | `boolean` | `true` | Plugin'i etkinleştir / devre dışı bırak |
| `preserveSpringBeans` | `boolean` | `true` | Spring annotasyonlarına göre otomatik koruma |
| `excludePackages` | `String[]` | — | İşlem dışı tutulacak paket adları |
| `flattenPackages` | `boolean` | `false` | Paket düzleştirmeyi etkinleştir |
| `flattenTargetPackage` | `String` | `""` | Düzleştirme hedef paketi |
| `mainClass` | `String` | — | (LEVEL_4) Gerçek Spring Boot main class |
| `encryptionKey` | `String` | — | (LEVEL_4) 64-char hex AES-256 anahtarı; boşsa rastgele üretilir |

Tüm parametreler `-Dobfuscation.<parametre>=<değer>` ile Maven komut satırından ezilebilir.

---

## pom.xml Profil Örnekleri

### Lokal Geliştirme — LEVEL_3

```xml
<profile>
  <id>spring-obfuscate</id>
  <build>
    <plugins>
      <plugin>
        <groupId>com.obfuscator</groupId>
        <artifactId>spring-obfuscator-maven-plugin</artifactId>
        <version>1.0.0</version>
        <executions>
          <execution>
            <id>obfuscate</id>
            <phase>process-classes</phase>
            <goals><goal>obfuscate</goal></goals>
          </execution>
        </executions>
        <configuration>
          <level>${obfuscation.level}</level>
          <preserveSpringBeans>true</preserveSpringBeans>
          <mainClass>com.example.MyApplication</mainClass>
          <flattenPackages>false</flattenPackages>
        </configuration>
      </plugin>
    </plugins>
  </build>
</profile>
```

Varsayılan değerleri `<properties>` bloğuna ekleyin:

```xml
<properties>
  <!-- LEVEL_4_ENCRYPTED: Docker/CI; LEVEL_3_ADVANCED: lokal -->
  <obfuscation.level>LEVEL_3_ADVANCED</obfuscation.level>
  <!-- LEVEL_4'te com.obfuscator.runtime.EncryptedLauncher olarak override edilir -->
  <obfuscation.startClass>com.example.MyApplication</obfuscation.startClass>
</properties>
```

### Üretim — LEVEL_4 + Paket Düzleştirme

```bash
mvn clean package \
  -P spring-obfuscate \
  -Dobfuscation.level=LEVEL_4_ENCRYPTED \
  -Dobfuscation.flattenPackages=true \
  -Dobfuscation.flattenTargetPackage=com.example.app \
  -Dobfuscation.encryptionKey=$(cat /run/secrets/obf_key)
```

---

## Docker / CI Entegrasyonu

> Anahtar ve Nexus kimlik bilgileri hiçbir Docker layer'a yazılmaz; BuildKit `--mount=type=secret` kullanılır.

### Dockerfile

```dockerfile
# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /build

ARG OBFUSCATION_LEVEL=LEVEL_3_ADVANCED

COPY maven-settings/docker-settings.xml /root/.m2/settings.xml
COPY pom.xml .

RUN --mount=type=secret,id=nexus_user \
    --mount=type=secret,id=nexus_pass \
    NEXUS_USERNAME=$(cat /run/secrets/nexus_user) \
    NEXUS_PASSWORD=$(cat /run/secrets/nexus_pass) \
    mvn -B dependency:go-offline -q

COPY . .

RUN --mount=type=secret,id=nexus_user \
    --mount=type=secret,id=nexus_pass \
    --mount=type=secret,id=obf_key \
    NEXUS_USERNAME=$(cat /run/secrets/nexus_user) \
    NEXUS_PASSWORD=$(cat /run/secrets/nexus_pass) \
    OBF_KEY=$(cat /run/secrets/obf_key 2>/dev/null || true) \
    mvn -B clean package -DskipTests \
        ${OBF_KEY:+-Dobfuscation.encryptionKey=$OBF_KEY} \
        -Dobfuscation.level=${OBFUSCATION_LEVEL} \
        -P spring-obfuscate

FROM eclipse-temurin:17-jre-jammy
COPY --from=builder /build/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Build Komutu

```bash
DOCKER_BUILDKIT=1 docker build \
  --secret id=nexus_user,env=NEXUS_USERNAME \
  --secret id=nexus_pass,env=NEXUS_PASSWORD \
  --secret id=obf_key,env=OBF_KEY \
  --build-arg OBFUSCATION_LEVEL=LEVEL_4_ENCRYPTED \
  -t myapp:latest .
```

### Lokal Geliştirici Scripti

```bash
# Kimlik bilgilerini .env dosyasına yazın (git'e commit etmeyin)
cp devops/scripts/.env.template devops/scripts/.env
# .env'yi doldurun, sonra:
bash devops/scripts/build-push.sh
```

### Jenkins Pipeline

Jenkins'te tanımlanması gereken Credentials:

| ID | Tür | Açıklama |
|----|-----|----------|
| `nexus-credentials` | Username/Password | Nexus kullanıcı adı ve şifresi |
| `obf-encryption-key` | Secret Text | AES-256 hex anahtarı |
| `gitlab-access-token` | Secret Text | GitLab OAuth token |
| `telegram-bot-token` | Secret Text | Telegram bildirim botu |

---

## Nexus'a Deploy

```bash
# ~/.m2/settings.xml içinde "nexus-releases" server tanımlı olmalı
bash scripts/deploy-plugin-to-nexus.sh
```

`pom.xml`'de Nexus plugin reposunu ekleyin:

```xml
<pluginRepositories>
  <pluginRepository>
    <id>nexus-releases</id>
    <url>http://nexus.example.com/repository/maven-releases</url>
    <releases><enabled>true</enabled></releases>
    <snapshots><enabled>false</enabled></snapshots>
  </pluginRepository>
</pluginRepositories>
```

---

## Sık Sorulan Sorular

**Uygulama başlamıyor, `NoSuchMethodError` alıyorum.**

`preserveSpringBeans=true` olduğunu kontrol edin. Eğer açıksa `@Service`, `@Component` gibi annotasyonlu sınıfların yalnızca `private` üyeleri yeniden adlandırılır; public API korunur. Sorun devam ederse ilgili sınıfı `excludePackages` listesine alın.

---

**LEVEL_4 ile JAR çalışmıyor.**

`spring-boot-maven-plugin` `<mainClass>` değerinin `com.obfuscator.runtime.EncryptedLauncher` olarak ayarlandığını doğrulayın. Komut satırından test:

```bash
java -cp target/myapp.jar com.obfuscator.runtime.EncryptedLauncher
```

---

**Her build'de farklı şifreli JAR mı üretilir?**

`encryptionKey` belirtilmezse her build için rastgele 32-byte anahtar üretilir — üretilen JAR'lar birbirleriyle uyumsuz olur. CI'da tekrar üretilebilirlik için `encryptionKey` parametresini Jenkins/Vault'tan secret olarak geçirin.

---

**Hangi sınıflar şifrelenmez (LEVEL_4)?**

`@Entity`, `@Table`, `@MappedSuperclass`, `@Configuration`, `@Bean`, `@SpringBootApplication` annotasyonlu sınıflar **FULL** korumalıdır; şifrelenmez, yeniden adlandırılmaz. Spring/JPA/Hibernate bunları yansıma ile yükleyeceği için isimlerinin sabit kalması zorunludur.

---

**flattenPackages ile Spring Boot çalışır mı?**

Evet — `ClassRemapper` tüm bytecode referanslarını (field descriptor, method signature, inner class attribute dahil) günceller. Spring, sınıfları yansıma değil classloader üzerinden yüklediği için paket adı önemsizdir. Yalnızca `@Entity` sınıfları için Hibernate alan adı → kolon adı eşlemesine dikkat edin; bu sınıflar zaten `FULL` korumalı olduğundan düzleştirme sırasında da atlanır.

---

## Proje Yapısı

```
spring-obfuscator-plugin/
├── src/main/java/com/obfuscator/
│   ├── ObfuscatorMojo.java              # Maven plugin giriş noktası
│   ├── ObfuscationLevel.java            # Seviye enum
│   ├── config/
│   │   ├── ObfuscationConfig.java       # Yapılandırma POJO
│   │   ├── ProtectionLevel.java         # FULL / PARTIAL / NONE
│   │   └── SpringAnnotationDetector.java# Annotasyon tarayıcı
│   ├── processor/
│   │   ├── ClassProcessor.java          # Pipeline orkestratörü
│   │   ├── Level1BasicObfuscator.java   # Alan/metot/yerel var renaming
│   │   ├── Level2StringEncryptor.java   # XOR string şifreleme
│   │   ├── Level3ControlFlowObfuscator.java # Kontrol akışı karmaşıklaştırma
│   │   ├── ClassEncryptionProcessor.java# AES-256-GCM sınıf şifreleme
│   │   └── PackageFlattenProcessor.java # Paket düzleştirme
│   ├── runtime/
│   │   ├── EncryptedClassLoader.java    # Çalışma zamanı AES çözücü
│   │   └── EncryptedLauncher.java       # Spring Boot Start-Class yedeği
│   └── util/
│       ├── BytecodeUtil.java
│       └── NameGenerator.java
└── scripts/
    └── deploy-plugin-to-nexus.sh
```

---

## Lisans

MIT License — © 2025 Sesasis A.Ş.
