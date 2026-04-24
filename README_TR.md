# <img src="https://raw.githubusercontent.com/Tarikul-Islam-Anik/Animated-Fluent-Emojis/master/Emojis/Travel%20and%20places/Fire.png" alt="Fire" width="35" height="35" /> Better Nothing Müzik Görselleştirici

## <img src="https://raw.githubusercontent.com/Tarikul-Islam-Anik/Animated-Fluent-Emojis/master/Emojis/Travel%20and%20places/Globe%20with%20Meridians.png" alt="Globe" width="25" height="25" /> Diğer dillerde oku: 🇺🇸 [English](README.md)

## <img src="https://raw.githubusercontent.com/Tarikul-Islam-Anik/Animated-Fluent-Emojis/master/Emojis/Smilies/Partying%20Face.png" alt="Partying Face" width="25" height="25" /> Önemli Duyuru
Şu anda bir uygulama geliştirmek için 2 geliştiriciyle birlikte çalışıyorum; bu uygulama, Android cihazdan canlı ses akışını yakalayan ve doğrudan gliflere (ışıklara) işleyen özel bir uygulama olacak. Bu uygulama "medya yansıtma" (media projection) özelliğini kullanacak; kulağa korkutucu bir izin gibi gelebilir ancak her uygulamadan yüksek kaliteli ses akışı almanın tek yolu bu. Spotify, YouTube Music ve istediğiniz hemen hemen her müzik uygulamasında çalan müziği görselleştirebileceğiniz anlamına geliyor! Yani her dosyayı elle (manuel) olarak işlemenize veya sadece yerel dosyaları kullanmanıza gerek kalmayacak! Kısacası, basit bir Python betiğinden harika bir Android uygulamasına geçiyoruz, böylece algoritmamızı kullanmak çok daha kolay olacak!  

## <img src="https://raw.githubusercontent.com/Tarikul-Islam-Anik/Animated-Fluent-Emojis/master/Emojis/Smilies/Thinking%20Face.png" alt="Thinking Face" width="25" height="25" /> Bu neden var?
Pek çok insan için (ben dahil), *Nothing tarafından sağlanan stok Glif Müzik Görselleştirme* özelliği dengesiz hissettiriyor.  
Teknik olarak öyle olmasa bile, müziğe verilen görsel tepki çok belirgin değil. Üstelik bu özellik, Glif Arayüzü'nün tüm potansiyelini gerçekten kullanmıyor. İşte bu yüzden de kendi müzik görselleştiricimi yaptım.

## <img src="https://fonts.gstatic.com/s/e/notoemoji/latest/2696_fe0f/512.gif" alt="⚖" width="32" height="32"> Stok vs Better Müzik Görselleştirici
| Özellik | Nothing Stok | **Better Müzik Görselleştirici** |
| :--- | :--- | :--- |
| **Işık seviyeleri** | ~2-bit derinlik (3 ışık seviyesi) | **12-bit derinlik (4096 ışık seviyesi)** |
| **Kare Hızı** | ~25 FPS | **60 FPS** |
| **Hassasiyet** | Rastgele hissettiriyor, senkronizasyonu görmek zor | **Her ışığın yoğunluğunu hassas bir şekilde belirlemek için FFT analizi kullanır** |
| **Bölgeler** | Standart, tüm fiziksel glifler kullanılır | **Her glif segmenti ve alt bölgesi bağımsız olarak kullanılır ve kontrol edilir** |
| **Görselleştirme yöntemi** | Sadece gerçek zamanlı | **20ms'ye kadar gecikme ile gerçek zamanlı veya önceden işlenmiş ses dosyaları** |

## <img src="https://fonts.gstatic.com/s/e/notoemoji/latest/1f3ac/512.gif" alt="🎬" width="40" height=""> [Video demoları ve örnekler](https://github.com/Aleks-Levet/better-nothing-music-visualizer/blob/main/Demo-video-examples.md)

### Farkı iş başında görün! [**Video demolarımıza göz atmak için buraya tıklayın!**](https://github.com/Aleks-Levet/better-nothing-music-visualizer/blob/main/Demo-video-examples.md)

## 📲 Desteklenen Nothing Phone Modelleri
Şu anda aşağıdaki modeller desteklenmektedir:
- Nothing Phone (1) 
  - Uygulama için glif hata ayıklama (debug) modunun **AÇIK** olması gerekir, bu bir *ADB komutu* ile ayarlanır: `adb shell settings put global nt_glyph_interface_debug_enable 1`. Nothing bize API anahtarını verdiğinde bu düzeltilecek.
- Nothing Phone (2)
- Nothing Phone (2a)
- Nothing Phone (2a Plus)
- Nothing Phone (3a)
- Nothing Phone (3a Pro)
- *Nothing Phone (3)* **(Beta, henüz tam kararlı değil)**
  
**Geliştirme aşamasında:**
- *Nothing Phone (4a)*

**Yakında gelecek:**
- *Nothing Phone (4a Pro)*


### <img src="https://fonts.gstatic.com/s/e/notoemoji/latest/2699_fe0f/512.gif" alt="⚙" width="25" height="25"> Nasıl çalışır (Teknik olarak)?
- Yüksek kaliteli bir ses akışı yakalanır.
- **FFT (Hızlı Fourier Dönüşümü)**, her **16.666 ms'lik kare** (60 FPS) için **20 ms'lik bir pencerede** frekansları analiz etmek amacıyla kullanılır, bu da görselleştirmeyi daha doğru hale getirir.
- Her glif bölgesi için **frekans aralıkları** `zones.config` dosyasında tanımlanmıştır ve tamamen özelleştirilebilir.
- Her glifin **parlaklığı**, kendisine atanan frekans aralığında bulunan **tepe büyüklüğü (peak magnitude)** ile tanımlanır.  
  Bu, farklı frekans "bölgelerinin" ne kadar yüksek olduğunu ölçer.
- Animasyonu daha pürüzsüz hale getirirken duyarlılığı korumak için **yalnızca aşağı yönlü yumuşatma** uygulanır (Bu da işin sırrı).
- Ardından gliflerde gösterilmeye hazır hale gelir!

## 📖 Yeni uygulama nasıl kullanılır?
Bunu kendiniz keşfedin, çünkü beni oku dosyası henüz hazır değil... Ha ha ha. (Üzgünüm)

## 📖 Python betiği nasıl kullanılır?
Kullanımı oldukça basit ve anlaşılır. Yine de kurulumu; kullanımı, yapılandırma dosyalarını ve sorun giderme bölümünü ayrıntılı olarak açıklayan detaylı bir wiki sayfası hazırladık. Ayrıca nasıl yeni ön ayarlar (presets) oluşturabileceğinizi de öğrenebilirsiniz (henüz değil gerçi). [**musicViz.py**'nin bir python betiği olarak nasıl kullanılacağını görmek için buraya tıklayın](https://github.com/Aleks-Levet/better-nothing-music-visualizer/wiki/). Bu ne kadar havalı biliyor musunuz? Sınırsız sayıda dosyayı sorunsuz bir şekilde toplu olarak dönüştürebilirsiniz!

## <img src="https://raw.githubusercontent.com/Tarikul-Islam-Anik/Animated-Fluent-Emojis/master/Emojis/Hand%20gestures/Handshake.png" alt="Handshake" width="25" height="25" /> Topluluğumuza katılın
Konuşmak veya tartışmak mı istiyorsunuz? *Hatalar, özellik istekleri?* [**Nothing sunucusundaki resmi discord başlığına katılmaktan çekinmeyin!**](https://discord.com/channels/930878214237200394/1434923843239280743)

## 🏗️ Katkıda Bulunma
Gelin ve bize yardım edin! Katkılarınız çok makbule geçer!
Şunları yapabilirsiniz:
- Sorun (issue) açabilirsiniz.
- Pull request gönderebilirsiniz.
- İyileştirme önerilerinde bulunabilirsiniz.
- Yeni görselleştirme fikirleri deneyebilirsiniz.
- Yeni ön ayarlar oluşturabilirsiniz.
- Geliştiricilerle tartışabilirsiniz.

##  <img src="https://fonts.gstatic.com/s/e/notoemoji/latest/1f512/512.gif" alt="🔒" width="25" height="25"> Güvenlik
**VirusTotal taramasının bağlantısını burada bulabilirsiniz:** https://www.virustotal.com/gui/url/c92c1ff82b56eb60bfd1e159592d09f949f0ea2d195e01f7f5adbef0e0b0385b?nocache=1

### <img src="https://raw.githubusercontent.com/Tarikul-Islam-Anik/Animated-Fluent-Emojis/master/Emojis/Symbols/Copyright.png" alt="Copyright" width="25" height="25" /> Emeği Geçenler:
#### İşte bu projede yer alan kişiler:
- [Aleks-Levet](https://github.com/Aleks-Levet) (Kurucu ve koordinatör, ana fikir, sahip)
- [Nicouschulas](https://github.com/Nicouschulas) (Beni oku (Readme) ve Wiki geliştirmeleri)
- [rKyzen(a.k.a Shivank Dan)](https://github.com/rKyzen)  (Gerçek zamanlı müzik akışı ile Android Uygulama geliştiricisi)
- [SebiAi](https://github.com/SebiAi) (Glif modlayıcı ve gliflerle ilgili yardımcı)
- [Earnedel-lab](https://github.com/Earendel-lab) (Beni oku (Readme) geliştirmeleri)
- [あけ なるかみ](https://github.com/Luke20YT) (Bu betikle entegre bir Müzik uygulaması yapan geliştirici)
- [Interlastic](https://github.com/Interlastic) (Betiği kolayca denemek için Discord Botu) (Kullanımdan kaldırıldı)

### <img src="https://raw.githubusercontent.com/Tarikul-Islam-Anik/Animated-Fluent-Emojis/master/Emojis/Travel%20and%20places/Star.png" alt="Star" width="25" height="25" /> Yıldız Geçmişi
![Yıldız Geçmişi](https://api.star-history.com/svg?repos=Aleks-Levet/better-nothing-music-visualizer&type=Date)
