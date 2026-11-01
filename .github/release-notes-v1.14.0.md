# NeriPlayer for Windows 1.14.0（預發布）

> **相容性提醒：本版不是 Android 原版的全功能同步版本。**
> 尚不支援原版目前使用的歸檔同步協議 3/4；偵測到 `neriplayer-sync-v3.manifest` 時會停止同步以保護資料。請勿刪除 manifest 繞過保護，也不要把本版當作已能與最新版 Android 歸檔雙向同步。

## 新增與改進

- Bilibili CC 字幕歌詞：人工 / AI 字幕回退、語言軌道選擇、獨立偏移與翻譯區間對齊（需平台登入與影片提供字幕）。
- 雙語歌詞分行與相近時間戳共同高亮，保留逐字歌詞、翻譯及音譯。
- 歌詞偏移支援數字輸入、50 ms 微調與單項重置，範圍為 ±5 秒。
- 原在線歌單與隊列優先使用已下載的本地封面。
- 短窗口播放控制區可捲動，避免播放按鈕被擠扁或隊列入口被裁切。
- 清空播放隊列前增加二次確認。

## 修復與保護

- 修復 WebDAV 寫後讀回失敗仍回報成功、HTTP 412 後無條件重寫等問題。
- 自動同步監聽歌單內容，失敗可重試；GitHub 讀取失敗不再當作空倉庫。
- 修復舊歌詞請求覆蓋新結果、播放準備期間暫停 / 清空、重複隊列排序與切歌統計歸屬問題。
- 隔離下載重試身份與暫存檔，避免取消後復活或同名歌曲互相覆蓋；標籤寫入後讀回驗證。
- 網路回應與 gzip 解壓限額、讀體逾時、下載取消、跨來源重定向憑據保護。

## 驗證

- 原始碼提交：`4e454ff7105224cf4e3b016496049eb3ffbb9684`（`update/upstream-2026-11`）。
- 本機隔離回歸測試：**66 項通過，0 失敗，0 跳過**；不是依賴真實帳號的端到端驗收。
- Windows 便攜版啟動、雙語歌詞、偏移保存、短窗口控制與隊列確認對話框已實機檢查。
- MSI / EXE 安裝包完成編譯；沒有執行真實安裝或舊版升級驗收。
- 詳細範圍與限制：[上游更新對照](https://github.com/Asternent/neriplayerforwindows/blob/4e454ff7105224cf4e3b016496049eb3ffbb9684/docs/UPSTREAM_SYNC.md)。

## 下載

| 檔案 | 用途 |
| --- | --- |
| `NeriPlayer-1.14.0.exe` | Windows 安裝程式 |
| `NeriPlayer-1.14.0.msi` | MSI 安裝包，適合管理部署 |
| `NeriPlayer-1.14.0-windows-portable.zip` | 解壓後執行 `NeriPlayer/NeriPlayer.exe`，免安裝 |
| `SHA256SUMS.txt` | 發布附件的 SHA-256 校驗值 |
| `TEST-SUMMARY.json` | 不含本機路徑與主機資訊的測試摘要 |

安裝包與便攜版內含 Java 執行環境，不需要另外安裝 JDK；ffmpeg 不內置。檔案未經程式碼簽署。
升級前請備份 `%APPDATA%\NeriPlayer` 與 `%LOCALAPPDATA%\NeriPlayerData`。

## 尚未移植

Navidrome / OpenSubsonic、YouTube Music、一起聽、自研 DSP / AutoEQ、USB 獨占、完整英文介面及新版歸檔同步；真實平台登入、SMTC / 媒體鍵與跨設備同步仍待獨立驗收。
