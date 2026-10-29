# Natuu v1.5 — বাংলা Setup Guide (দ্রুত কথা বলা + তোমার Voice Pack)

## এই ভার্সনে নতুন কী (v1.5.0)

1. **খুব দ্রুত reply** — AI-র উত্তর আসতে থাকলেই কথা বলা শুরু হয়। আগে পুরো উত্তর
   লেখা শেষ হওয়া পর্যন্ত অপেক্ষা করতে হতো, এখন প্রথম বাক্য তৈরি হওয়া
   মাত্রই voice এ বলা শুরু হয়।
2. **Voice pack এর ভয়েস সবখানে** — zip এর মধ্যে থাকা voice samples গুলো থেকে
   বানানো cloned voice দিয়েই প্রতিটি উত্তর বলা হবে (আগে offline উত্তরে ফোনের
   robot voice বলত)।
3. **Hands-free কথোপকথন** — একটি উত্তর বলা শেষ হওয়া মাত্র Natuu আবার
   শোনা শুরু করে; আর কথা বললেই তার উত্তর আসে। এটা Settings →
   **Hands-free conversation** দিয়ে চালু/বন্ধ করা যায়।
4. **ছোট উত্তর** — normal কথোপকথনের উত্তর এখন সংক্ষিপ্ত, তাই দ্রুতও।
   (Reminder, app খোলা, call — সব আগের মতোই কাজ করে।)

## ফোনে install করা

1. `app-debug.apk` টা ফোনে নিন (USB / file share / cloud — যেভাবে সুবিধা)।
2. ফোনে ট্যাপ করে খুললে **Install unknown apps** এর জন্য অনুমতি চাইতে পারে;
   দিয়ে দিন। Play Protect সতর্কতা দিলে **Install anyway** চাপুন (এটা নিজের
   build করা APK)।
3. Install করে খুলুন — প্রথমবার **Microphone** ও **Notification** permission
   দিন।

## প্রথমবার Setup (৩টি জিনিস)

### ১) OpenRouter key (AI উত্তরের জন্য)

- https://openrouter.ai/settings/keys এ গিয়ে free account থেকে key নিন
- Natuu Settings → **OpenRouter API key** এ paste করে Save করুন
- Free model গুলো auto router এর মাধ্যমে চলে (OpenRouter Free Router)

### ২) Bangla + English speech pack (তোমার কথা শোনার জন্য)

- Settings → **Download Bangla pack** এবং **Download English pack** চাপুন
- কাজ না করলে → **Open speech language settings** থেকে ফোনের offline voice
  download করুন (একবারই লাগে)
- এরপর মাইকে কথা বললে বাংলা/ইংরেজি সোজা বুঝবে

### ৩) Voice pack ভয়েস (তোমার নিজের ভয়েসে উত্তর)

App এর ভিতরে zip এর 5টা voice sample আগে থেকেই আছে। ওই ভয়েসে উত্তর
শুনতে ElevenLabs (free) ব্যবহার করতে হবে:

- https://elevenlabs.io এ ফ্রি account খুলুন (মাসে বিনামূল্যে কিছু quota দেয়)
- Dashboard → API keys থেকে **ElevenLabs API key** copy করুন
- Natuu Settings → **ElevenLabs API key** এ paste করুন
- নিচের ownership checkbox টিক দিন (নিজের ভয়েস / permission আছে)
- **Create my custom voice** চাপুন — samples থেকে ভয়েস বানিয়ে নেবে
- **Test English voice** / **Test Bangla voice** দিয়ে শুনে নিন

এরপর **প্রতিটা উত্তর** ওই ভয়েসেই বলা হবে (বাংলা + ইংরেজি দুটোতেই)।

Note: Voice synthesis এ internet লাগে; দুর্বল হলে মুহূর্তের জন্য ফোনের TTS
voice ব্যবহার করে উত্তর পড়ে। Quota শেষ হলেও একই হবে।

## দৈনন্দিন ব্যবহার

- 🎙️ মাইকে ট্যাপ করে বলুন — বলা শেষ করার সাথে সাথে উত্তরের শুরুটা আসবে
- **Hands-free** চালু থাকলে উত্তর শোনা শেষ হওয়া মাত্র আবার শোনা শুরু হবে,
  এভাবে টানা কথোপকথন চলবে; থামাতে **⏹** বাটন বা hands-free বন্ধ করুন
- Text ও লিখতে পারবেন — উত্তর একইভাবে ভয়েসে শোনাবে
- "Remind me...", "Open YouTube" এর মতো কমান্ডও কাজ করে (নেটিভ ফিচার)
- Settings থেকে "Hey Natu" wake word ও চালু রাখা যায়

## অফলাইন AI (optional)

Settings এ ~586 MB এর offline model ডাউনলোড করা যায় — internet ছাড়া
উত্তর আসবে (তখন ভয়েস ফোনের TTS)। Normal ব্যবহারে online mode অনেক
দ্রুত ও ভালো; বরং দরকার হলে নিন।

## Troubleshooting

- **উত্তর robot voice এ বলছে** — ElevenLabs key/voice setup হয়নি, বা quota
  শেষ। Settings এ test voice দিয়ে চেক করুন।
- **তোমার কথা বুঝছে না** — Bangla + English speech pack ডাউনলোড করুন;
  দরকার হলে internet চালু রাখুন।
- **Reply খুব ধীর** — স্বাভাবিক নয়; ফোন ফিরে খুললে ঠিক হয়, বা অন্য
  free model পাওয়া যায় কিনা দেখুন (router নিজেই চেষ্টা করে)।
