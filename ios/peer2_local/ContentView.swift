import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import Foundation
import WebRTC

private enum MessengerSection: String, CaseIterable {
    case chats = "Чаты"
    case groups = "Группы"
    case services = "Сервисы"
}

struct ContentView: View {
    @StateObject private var appState = MeshAppState()

    var body: some View {
        ZStack {
            AppBackground()
            TabView(selection: $appState.selectedTab) {
                MessengerRootView(appState: appState)
                    .tag(AppTab.messenger)
                    .tabItem {
                        Label("Мессенджер", systemImage: "message.fill")
                    }

                FilePoolView(appState: appState)
                    .tag(AppTab.files)
                    .tabItem {
                        Label("Файлы", systemImage: "icloud.fill")
                    }

                CallsView(appState: appState)
                    .tag(AppTab.calls)
                    .tabItem {
                        Label("Звонки", systemImage: "phone.fill")
                    }

                SettingsView(appState: appState)
                    .tag(AppTab.settings)
                    .tabItem {
                        Label("Настройки", systemImage: "gearshape.fill")
                    }
            }
            .tint(statusColor)

            if let incoming = appState.incomingCall {
                IncomingCallOverlay(call: incoming, appState: appState)
                    .transition(.opacity)
            }

            if let active = appState.activeCall {
                ActiveCallOverlay(call: active, appState: appState)
                    .transition(.opacity)
            }
        }
        .preferredColorScheme(.dark)
    }

    private var statusColor: Color {
        appState.peers.isEmpty ? .blue : .green
    }
}

private struct AppBackground: View {
    var body: some View {
        LinearGradient(
            colors: [
                Color(red: 0.08, green: 0.10, blue: 0.15),
                Color(red: 0.10, green: 0.13, blue: 0.22),
                Color(red: 0.14, green: 0.18, blue: 0.28)
            ],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .ignoresSafeArea()
    }
}

private struct HeaderTitle: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.system(size: 28, weight: .bold, design: .rounded))
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(
                LinearGradient(colors: [Color(red: 0.67, green: 0.44, blue: 0.88), Color(red: 0.59, green: 0.70, blue: 0.91)], startPoint: .leading, endPoint: .trailing),
                in: RoundedRectangle(cornerRadius: 8)
            )
    }
}

private struct MessengerRootView: View {
    @ObservedObject var appState: MeshAppState

    @State private var section: MessengerSection = .chats
    @State private var selectedGroupId: String?
    @State private var showCreateGroup = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                topBar

                Picker("Section", selection: $section) {
                    ForEach(MessengerSection.allCases, id: \.self) { option in
                        Text(option.rawValue).tag(option)
                    }
                }
                .pickerStyle(.segmented)

                if section == .services {
                    servicesPlaceholder
                } else if section == .groups {
                    groupsList
                } else {
                    chatsList
                }
            }
            .padding(.horizontal, 16)
            .navigationDestination(isPresented: chatNavActiveBinding) {
                if let peerId = appState.selectedPeerId {
                    ChatDetailView(appState: appState, peerId: peerId)
                }
            }
            .navigationDestination(isPresented: groupNavActiveBinding) {
                if let groupId = selectedGroupId {
                    GroupDetailView(appState: appState, groupId: groupId)
                }
            }
            .sheet(isPresented: $showCreateGroup) {
                GroupCreateView(appState: appState)
            }
            .toolbar(.hidden, for: .navigationBar)
        }
    }

    private var chatNavActiveBinding: Binding<Bool> {
        Binding<Bool>(
            get: {
                appState.selectedPeerId != nil
            },
            set: { presented in
                if !presented {
                    appState.selectedPeerId = nil
                }
            }
        )
    }

    private var groupNavActiveBinding: Binding<Bool> {
        Binding<Bool>(
            get: {
                selectedGroupId != nil
            },
            set: { presented in
                if !presented {
                    selectedGroupId = nil
                }
            }
        )
    }

    private var topBar: some View {
        HStack {
            HeaderTitle(title: "PeerDone")
            Spacer()
            Text(appState.connectionStatusLabel)
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(Color.white.opacity(0.12), in: Capsule())
        }
        .padding(.top, 12)
    }

    private var chatsList: some View {
        ScrollView {
            VStack(spacing: 12) {
                ForEach(chatPeerIds, id: \.self) { peerId in
                    let messages = appState.privateChats[peerId] ?? []
                    let last = messages.last
                    ChatRow(
                        title: appState.displayName(for: peerId),
                        subtitle: last?.text ?? "Нет сообщений",
                        time: formatShortTime(last?.timestampMs ?? 0),
                        online: appState.peers.contains(where: { $0.userId == peerId }),
                        unreadCount: appState.unreadByPeer[peerId] ?? 0
                    )
                    .onTapGesture {
                        appState.openChat(peerId: peerId)
                    }
                }

                if chatPeerIds.isEmpty {
                    EmptyState(title: "Нет чатов", subtitle: "Появятся после обнаружения пиров и сообщений", icon: "bubble.left.and.bubble.right")
                }
            }
        }
    }

    private var groupsList: some View {
        VStack(spacing: 12) {
            ScrollView {
                VStack(spacing: 10) {
                    ForEach(appState.groups) { group in
                        let messages = appState.groupChats[group.id] ?? []
                        let last = messages.last
                        HStack(spacing: 12) {
                            Circle()
                                .fill(Color.white.opacity(0.2))
                                .frame(width: 52, height: 52)
                                .overlay(Text(String(group.name.prefix(1)).uppercased()).font(.title3.bold()))

                            VStack(alignment: .leading, spacing: 4) {
                                Text(group.name)
                                    .font(.headline)
                                Text(last?.text ?? "Нет сообщений")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                            Spacer()
                            Text(formatShortTime(last?.timestampMs ?? 0))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .padding(12)
                        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 18))
                        .onTapGesture {
                            selectedGroupId = group.id
                        }
                    }

                    if appState.groups.isEmpty {
                        EmptyState(title: "Нет групп", subtitle: "Создайте группу и добавьте участников онлайн", icon: "person.3")
                    }
                }
            }

            Button {
                showCreateGroup = true
            } label: {
                HStack {
                    Image(systemName: "plus.circle.fill")
                    Text("Создать группу")
                        .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(Color.white.opacity(0.16), in: RoundedRectangle(cornerRadius: 14))
            }
            .padding(.bottom, 8)
        }
    }

    private var servicesPlaceholder: some View {
        VStack(spacing: 12) {
            EmptyState(title: "Сервисы пока неактивны", subtitle: "Раздел подготовлен для следующих релизов", icon: "sparkles")
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    private var chatPeerIds: [String] {
        let fromMessages = Array(appState.privateChats.keys)
        let online = appState.peers.map(\.userId)
        let peers = Array(Set(fromMessages + online))
        return peers.sorted { lhs, rhs in
            let lhsTs = appState.privateChats[lhs]?.last?.timestampMs ?? 0
            let rhsTs = appState.privateChats[rhs]?.last?.timestampMs ?? 0
            if lhsTs != rhsTs {
                return lhsTs > rhsTs
            }
            return appState.displayName(for: lhs).localizedCaseInsensitiveCompare(appState.displayName(for: rhs)) == .orderedAscending
        }
    }
}

private struct ChatRow: View {
    let title: String
    let subtitle: String
    let time: String
    let online: Bool
    let unreadCount: Int

    var body: some View {
        HStack(spacing: 12) {
            ZStack(alignment: .bottomTrailing) {
                Circle()
                    .fill(Color.white.opacity(0.2))
                    .frame(width: 52, height: 52)
                    .overlay(
                        Text(String(title.prefix(1)).uppercased())
                            .font(.title3.bold())
                    )

                if online {
                    Circle()
                        .fill(.green)
                        .frame(width: 12, height: 12)
                }
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.headline)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 6) {
                Text(time)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if unreadCount > 0 {
                    Text("\(min(unreadCount, 99))")
                        .font(.caption2.weight(.bold))
                        .padding(6)
                        .background(Color.blue, in: Circle())
                }
            }
        }
        .padding(12)
        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 18))
    }
}

private struct ChatDetailView: View {
    @ObservedObject var appState: MeshAppState
    let peerId: String

    @State private var draft = ""
    @State private var showFilePicker = false
    @State private var showAttachmentSource = false
    @State private var showPhotoPicker = false
    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var showDeleteAlert = false
    @State private var showCallMenu = false
    @State private var showMicAlert = false
    @State private var showScrollToBottom = false
    @StateObject private var recorder = VoiceNoteRecorder()
    @StateObject private var voicePlayer = VoiceNotePlayer()

    var body: some View {
        VStack(spacing: 0) {
            header

            GeometryReader { geometry in
                ScrollViewReader { proxy in
                    ZStack(alignment: .bottomTrailing) {
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 10) {
                                ForEach(messages) { message in
                                    MessageBubble(
                                        message: message,
                                        isVoicePlaying: voicePlayer.playingMessageId == message.id && voicePlayer.isPlaying,
                                        onVoiceTap: {
                                            guard let path = appState.resolveExistingFilePath(filePath: message.filePath, fileName: message.fileName) else { return }
                                            voicePlayer.togglePlayback(messageID: message.id, filePath: path)
                                        }
                                    )
                                        .id(message.id)
                                }

                                GeometryReader { sentinel in
                                    Color.clear.preference(
                                        key: ChatBottomOffsetPreferenceKey.self,
                                        value: sentinel.frame(in: .named("chat-scroll")).maxY
                                    )
                                }
                                .frame(height: 1)
                                .id("chat-bottom-anchor")
                            }
                            .padding(12)
                        }
                        .coordinateSpace(name: "chat-scroll")
                        .onPreferenceChange(ChatBottomOffsetPreferenceKey.self) { bottomY in
                            let hiddenDistance = bottomY - geometry.size.height
                            showScrollToBottom = hiddenDistance > 44
                        }
                        .onChange(of: messages.count) { _ in
                            appState.markChatRead(peerId: peerId)
                            if let last = messages.last {
                                withAnimation {
                                    proxy.scrollTo(last.id, anchor: .bottom)
                                }
                            }
                        }

                        if showScrollToBottom {
                            Button {
                                withAnimation(.easeOut(duration: 0.2)) {
                                    if let last = messages.last {
                                        proxy.scrollTo(last.id, anchor: .bottom)
                                    } else {
                                        proxy.scrollTo("chat-bottom-anchor", anchor: .bottom)
                                    }
                                }
                            } label: {
                                Image(systemName: "arrow.down")
                                    .font(.headline.bold())
                                    .frame(width: 42, height: 42)
                                    .background(Color.blue.opacity(0.92), in: Circle())
                            }
                            .padding(.trailing, 14)
                            .padding(.bottom, 12)
                        }
                    }
                }
            }

            if let progress = fileProgress {
                FileTransferProgressRow(progress: progress, debug: fileTransferDebug)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 8)
            }

            if recorder.isRecording {
                recordingBar
            } else {
                inputBar
            }
        }
        .background(Color.clear)
        .onAppear {
            appState.markChatRead(peerId: peerId)
        }
        .fileImporter(isPresented: $showFilePicker, allowedContentTypes: [.item], allowsMultipleSelection: false) { result in
            guard case .success(let urls) = result, let url = urls.first else { return }
            appState.sendFile(to: peerId, fileURL: url)
        }
        .confirmationDialog("Добавить вложение", isPresented: $showAttachmentSource, titleVisibility: .visible) {
            Button("Из файлов") { showFilePicker = true }
            Button("Из галереи") { showPhotoPicker = true }
            Button("Отмена", role: .cancel) {}
        }
        .photosPicker(
            isPresented: $showPhotoPicker,
            selection: $selectedPhotoItem,
            matching: .any(of: [.images, .videos])
        )
        .onChange(of: selectedPhotoItem) { item in
            guard let item else { return }
            Task { await sendGalleryItem(item) }
        }
        .confirmationDialog("Выберите звонок", isPresented: $showCallMenu, titleVisibility: .visible) {
            Button("Голосовой") { appState.startCall(peerId: peerId, video: false) }
            Button("Видео") { appState.startCall(peerId: peerId, video: true) }
        }
        .alert("Удалить чат?", isPresented: $showDeleteAlert) {
            Button("Удалить", role: .destructive) { appState.removeChat(peerId: peerId) }
            Button("Отмена", role: .cancel) {}
        }
        .alert("Нет доступа к микрофону", isPresented: $showMicAlert) {
            Button("Ок", role: .cancel) {}
        } message: {
            Text("Разрешите доступ к микрофону в настройках iOS для записи голосовых.")
        }
    }

    private var header: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(Color.white.opacity(0.18))
                .frame(width: 44, height: 44)
                .overlay(Text(String(appState.displayName(for: peerId).prefix(1)).uppercased()).font(.headline.bold()))

            VStack(alignment: .leading, spacing: 2) {
                Text(appState.displayName(for: peerId))
                    .font(.headline)
                Text(appState.peers.contains(where: { $0.userId == peerId }) ? "В сети" : "Не в сети")
                    .font(.caption)
                    .foregroundStyle(appState.peers.contains(where: { $0.userId == peerId }) ? .green : .secondary)
            }

            Spacer()

            Button {
                showCallMenu = true
            } label: {
                Image(systemName: "phone.fill")
                    .padding(10)
                    .background(Color.white.opacity(0.12), in: Circle())
            }

            Menu {
                Button("Видео-звонок") { appState.startCall(peerId: peerId, video: true) }
                Button("Удалить чат", role: .destructive) { showDeleteAlert = true }
            } label: {
                Image(systemName: "ellipsis.circle.fill")
                    .font(.title3)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Color.white.opacity(0.08))
    }

    private var inputBar: some View {
        HStack(spacing: 10) {
            Button {
                showAttachmentSource = true
            } label: {
                Image(systemName: "plus")
                    .font(.title3.bold())
                    .frame(width: 42, height: 42)
                    .background(Color.white.opacity(0.16), in: Circle())
            }

            TextField("Отправьте сообщение", text: $draft)
                .textFieldStyle(.plain)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(Color.white.opacity(0.12), in: Capsule())

            if draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Button {
                } label: {
                    Image(systemName: "mic.fill")
                        .frame(width: 42, height: 42)
                        .background(Color.blue.opacity(0.9), in: Circle())
                }
                .simultaneousGesture(
                    LongPressGesture(minimumDuration: 0.25)
                        .onEnded { _ in
                            recorder.start { success in
                                if !success {
                                    showMicAlert = true
                                }
                            }
                        }
                )
            } else {
                Button {
                    appState.sendText(to: peerId, text: draft)
                    draft = ""
                } label: {
                    Image(systemName: "paperplane.fill")
                        .frame(width: 42, height: 42)
                        .background(Color.blue.opacity(0.9), in: Circle())
                }
            }
        }
        .padding(10)
        .background(.ultraThinMaterial)
    }

    private var recordingBar: some View {
        HStack {
            Button("Отменить") {
                recorder.cancel()
            }
            .foregroundStyle(.red)

            Spacer()

            VStack(spacing: 6) {
                ZStack {
                    Circle()
                        .fill(Color.red.opacity(0.24))
                        .frame(
                            width: 40 + recorder.normalizedLevel * 36,
                            height: 40 + recorder.normalizedLevel * 36
                        )
                    Circle()
                        .fill(Color.red)
                        .frame(width: 42, height: 42)
                        .overlay(Image(systemName: "mic.fill").foregroundStyle(.white))
                }
                .animation(.easeOut(duration: 0.08), value: recorder.normalizedLevel)
                Text("Запись \(recorder.durationLabel)")
                    .font(.caption)
            }

            Spacer()

            Button("Отправить") {
                if let result = recorder.finish() {
                    appState.sendVoiceNote(to: peerId, fileURL: result.url, durationMs: result.durationMs)
                }
            }
            .foregroundStyle(.green)
        }
        .padding(14)
        .background(.ultraThinMaterial)
    }

    private var messages: [ChatMessageItem] {
        appState.privateChats[peerId] ?? []
    }

    private var fileProgress: Double? {
        if let debug = fileTransferDebug {
            let total = max(debug.totalChunks, debug.ackedChunks + debug.pendingChunks + debug.failedChunks, 1)
            let isComplete = debug.pendingChunks == 0
                && (debug.ackedChunks + debug.failedChunks) >= total
            if isComplete {
                return nil
            }
            let delivered = Double(debug.ackedChunks)
            return min(max(delivered / Double(total), 0), 1)
        }
        guard let progress = appState.outboundFileProgressByPeer[peerId] else { return nil }
        return min(max(progress, 0), 1)
    }

    private var fileTransferDebug: FileTransferDebugMetrics? {
        appState.outboundFileDebugByPeer[peerId]
    }

    @MainActor
    private func sendGalleryItem(_ item: PhotosPickerItem) async {
        defer { selectedPhotoItem = nil }
        guard let data = try? await item.loadTransferable(type: Data.self), !data.isEmpty else { return }
        let type = item.supportedContentTypes.first
        let isVideo = type?.conforms(to: .movie) == true
        let ext = type?.preferredFilenameExtension ?? (isVideo ? "mov" : "jpg")
        let prefix = isVideo ? "video" : "photo"
        let tempURL = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("\(prefix)_\(UUID().uuidString.prefix(8)).\(ext)")
        do {
            try data.write(to: tempURL, options: .atomic)
            appState.sendFile(to: peerId, fileURL: tempURL)
            try? FileManager.default.removeItem(at: tempURL)
        } catch {
            return
        }
    }
}

private struct ChatBottomOffsetPreferenceKey: PreferenceKey {
    static var defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

private struct MessageBubble: View {
    let message: ChatMessageItem
    let isVoicePlaying: Bool
    let onVoiceTap: () -> Void

    var body: some View {
        HStack {
            if message.isOutgoing { Spacer(minLength: 44) }

            VStack(alignment: .leading, spacing: 6) {
                if message.type == "file" {
                    Text(message.fileName ?? message.text)
                        .font(.subheadline.weight(.semibold))
                } else if message.type == "voice" {
                    HStack(spacing: 8) {
                        Button(action: onVoiceTap) {
                            Image(systemName: isVoicePlaying ? "pause.circle.fill" : "play.circle.fill")
                                .font(.title2)
                                .frame(width: 34, height: 34)
                        }
                        .buttonStyle(.plain)
                        .contentShape(Rectangle())
                        Label("Голосовое сообщение", systemImage: "waveform")
                            .font(.subheadline)
                    }
                } else if message.type == "video_note" {
                    Label("Видеосообщение", systemImage: "diamond.fill")
                        .font(.subheadline)
                } else {
                    Text(message.text)
                        .font(.body)
                }

                HStack(spacing: 8) {
                    if message.type != "voice", let path = message.filePath, !path.isEmpty {
                        let fileURL = URL(fileURLWithPath: path)
                        if FileManager.default.fileExists(atPath: fileURL.path) {
                            if message.type == "file" {
                                ShareLink(item: fileURL) {
                                    Label("Скачать", systemImage: "arrow.down.circle")
                                        .font(.caption)
                                }
                            } else {
                                Link("Открыть", destination: fileURL)
                                    .font(.caption)
                            }
                        }
                    }
                    Text(formatShortTime(message.timestampMs))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(12)
            .background(message.isOutgoing ? Color.blue.opacity(0.35) : Color.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 16))

            if !message.isOutgoing { Spacer(minLength: 44) }
        }
    }
}

private struct FileTransferProgressRow: View {
    let progress: Double
    let debug: FileTransferDebugMetrics?

    var body: some View {
        HStack(spacing: 10) {
            ZStack {
                Circle()
                    .stroke(Color.white.opacity(0.22), lineWidth: 4)
                    .frame(width: 28, height: 28)
                Circle()
                    .trim(from: 0, to: progress)
                    .stroke(Color.blue, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .frame(width: 28, height: 28)
                    .animation(.linear(duration: 0.12), value: progress)
            }

            Text("Отправка файла \(Int(progress * 100))%")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)

            if let debug {
                let total = max(debug.totalChunks, debug.ackedChunks + debug.pendingChunks + debug.failedChunks, 1)
                Text("ACK \(debug.ackedChunks)/\(total) · ждёт \(debug.pendingChunks) · ретраи \(debug.retriedChunks) · ошибки \(debug.failedChunks)")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct GroupDetailView: View {
    @ObservedObject var appState: MeshAppState
    let groupId: String

    @Environment(\.dismiss) private var dismiss
    @State private var draft = ""
    @FocusState private var inputFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            if let group = appState.groups.first(where: { $0.id == groupId }) {
                HStack {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "chevron.left")
                            .font(.headline.weight(.semibold))
                            .frame(width: 32, height: 32)
                            .background(Color.white.opacity(0.14), in: Circle())
                    }

                    Circle()
                        .fill(Color.white.opacity(0.2))
                        .frame(width: 42, height: 42)
                        .overlay(Text(String(group.name.prefix(1)).uppercased()).font(.headline.bold()))
                    VStack(alignment: .leading) {
                        Text(group.name)
                            .font(.headline)
                        Text("Участников: \(group.participantIds.count)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                .padding(12)
                .background(Color.white.opacity(0.08))
            }

            ScrollView {
                LazyVStack(spacing: 10) {
                    ForEach(appState.groupChats[groupId] ?? []) { msg in
                        HStack {
                            if msg.isOutgoing { Spacer() }
                            VStack(alignment: .leading, spacing: 4) {
                                if !msg.isOutgoing {
                                    Text(appState.displayName(for: msg.senderId))
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(colorForSender(msg.senderId))
                                }
                                Text(msg.text)
                                Text(formatShortTime(msg.timestampMs))
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            .padding(10)
                            .background(msg.isOutgoing ? Color.blue.opacity(0.3) : Color.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
                            if !msg.isOutgoing { Spacer() }
                        }
                    }
                }
                .padding(12)
            }

            HStack(spacing: 10) {
                TextField("Сообщение в группу", text: $draft)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .background(Color.white.opacity(0.12), in: Capsule())
                    .focused($inputFocused)

                Button {
                    appState.sendGroupText(groupId: groupId, text: draft)
                    draft = ""
                } label: {
                    Image(systemName: "paperplane.fill")
                        .frame(width: 42, height: 42)
                        .background(Color.blue, in: Circle())
                }
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(10)
            .background(.ultraThinMaterial)
        }
        .toolbar(.hidden, for: .navigationBar)
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button {
                    inputFocused = false
                } label: {
                    Image(systemName: "keyboard.chevron.compact.down")
                }
            }
        }
    }

    private func colorForSender(_ id: String) -> Color {
        let palette: [Color] = [.orange, .pink, .cyan, .mint, .yellow, .purple]
        return palette[abs(id.hashValue) % palette.count]
    }
}

private struct GroupCreateView: View {
    @ObservedObject var appState: MeshAppState
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var selected: Set<String> = []

    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                TextField("Название группы", text: $name)
                    .padding(12)
                    .background(Color.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))

                ScrollView {
                    VStack(spacing: 8) {
                        ForEach(appState.peers) { peer in
                            Button {
                                if selected.contains(peer.userId) {
                                    selected.remove(peer.userId)
                                } else {
                                    selected.insert(peer.userId)
                                }
                            } label: {
                                HStack {
                                    Text(appState.displayName(for: peer.userId))
                                    Spacer()
                                    Image(systemName: selected.contains(peer.userId) ? "checkmark.circle.fill" : "plus.circle")
                                }
                                .padding(12)
                                .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                Button {
                    appState.createGroup(name: name, participants: Array(selected))
                    dismiss()
                } label: {
                    Text("Создать")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(canCreate ? Color.green : Color.gray.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
                }
                .disabled(!canCreate)
            }
            .padding(16)
            .navigationTitle("Новая группа")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Закрыть") { dismiss() }
                }
            }
        }
    }

    private var canCreate: Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !selected.isEmpty
    }
}

private struct FilePoolView: View {
    @ObservedObject var appState: MeshAppState

    private enum FileTab: String, CaseIterable {
        case mine = "Мои файлы"
        case remote = "Чужие файлы"
    }

    @State private var selectedTab: FileTab = .mine
    @State private var showPicker = false
    @State private var showSourceDialog = false
    @State private var showPhotoPicker = false
    @State private var selectedPhotoItem: PhotosPickerItem?

    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                HeaderTitle(title: "Файловый пул")
                    .frame(maxWidth: .infinity, alignment: .leading)

                fileTabs

                if selectedTab == .mine {
                    myFilesView
                } else {
                    remoteFilesView
                }

                Spacer()
            }
            .padding(16)
            .toolbar(.hidden, for: .navigationBar)
            .fileImporter(isPresented: $showPicker, allowedContentTypes: [.item], allowsMultipleSelection: false) { result in
                guard case .success(let urls) = result, let url = urls.first else { return }
                appState.addStreamFile(from: url)
            }
            .confirmationDialog("Добавить файл в стрим", isPresented: $showSourceDialog, titleVisibility: .visible) {
                Button("Из файлов") { showPicker = true }
                Button("Из галереи") { showPhotoPicker = true }
                Button("Отмена", role: .cancel) {}
            }
            .photosPicker(
                isPresented: $showPhotoPicker,
                selection: $selectedPhotoItem,
                matching: .any(of: [.images, .videos])
            )
            .onChange(of: selectedPhotoItem) { item in
                guard let item else { return }
                Task { await addStreamFromGallery(item) }
            }
        }
    }

    private var fileTabs: some View {
        HStack(spacing: 8) {
            tabButton(tab: .mine, color: appState.myStreamFiles.isEmpty ? .white : .purple)
            tabButton(tab: .remote, color: .white)
        }
        .padding(4)
        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
    }

    private func tabButton(tab: FileTab, color: Color) -> some View {
        Button {
            selectedTab = tab
        } label: {
            Text(tab.rawValue)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(selectedTab == tab ? color : color.opacity(0.75))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 9)
                .background(
                    selectedTab == tab
                    ? Color.white.opacity(0.16)
                    : Color.clear,
                    in: RoundedRectangle(cornerRadius: 9)
                )
        }
        .buttonStyle(.plain)
    }

    private var myFilesView: some View {
        VStack(spacing: 10) {
            Button {
                showSourceDialog = true
            } label: {
                HStack {
                    Image(systemName: "plus.circle.fill")
                    Text("Добавить в стрим")
                        .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(Color.white.opacity(0.16), in: RoundedRectangle(cornerRadius: 14))
            }

            ScrollView {
                VStack(spacing: 8) {
                    ForEach(appState.myStreamFiles) { item in
                        HStack(spacing: 10) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(item.fileName).font(.headline)
                                Text(byteLabel(item.totalBytes))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("Выключить") {
                                appState.stopStreaming(fileId: item.id)
                            }
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 7)
                            .background(Color.red.opacity(0.22), in: Capsule())
                        }
                        .padding(12)
                        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                    }

                    if appState.myStreamFiles.isEmpty {
                        EmptyState(
                            title: "Вы ничего не стримите",
                            subtitle: "Добавьте файл через плюс, чтобы он появился у пиров в разделе чужих файлов",
                            icon: "icloud.and.arrow.up"
                        )
                    }
                }
            }
        }
        .padding(12)
        .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16))
    }

    @MainActor
    private func addStreamFromGallery(_ item: PhotosPickerItem) async {
        defer { selectedPhotoItem = nil }
        guard let data = try? await item.loadTransferable(type: Data.self), !data.isEmpty else { return }
        let type = item.supportedContentTypes.first
        let isVideo = type?.conforms(to: .movie) == true
        let ext = type?.preferredFilenameExtension ?? (isVideo ? "mov" : "jpg")
        let prefix = isVideo ? "stream_video" : "stream_photo"
        let tempURL = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("\(prefix)_\(UUID().uuidString.prefix(8)).\(ext)")
        do {
            try data.write(to: tempURL, options: .atomic)
            appState.addStreamFile(from: tempURL)
            try? FileManager.default.removeItem(at: tempURL)
        } catch {
            return
        }
    }

    private var remoteFilesView: some View {
        ScrollView {
            VStack(spacing: 8) {
                ForEach(appState.remoteStreamFiles) { item in
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(item.fileName).font(.headline)
                            Text("Владелец: \(appState.displayName(for: item.ownerId))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Text(byteLabel(item.totalBytes))
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 8) {
                            Button {
                                appState.removeRemoteStreamFile(fileId: item.id, ownerId: item.ownerId)
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.headline)
                                    .foregroundStyle(.secondary)
                            }
                            .buttonStyle(.plain)

                            Button("Скачать") {
                                appState.requestStreamDownload(fileId: item.id, ownerId: item.ownerId)
                            }
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 7)
                            .background(Color.blue.opacity(0.24), in: Capsule())
                        }
                    }
                    .padding(12)
                    .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                }

                if appState.remoteStreamFiles.isEmpty {
                    EmptyState(
                        title: "Нет чужих файлов",
                        subtitle: "Когда пиры добавят стрим-файл, он появится тут и станет доступен для скачивания",
                        icon: "icloud.and.arrow.down"
                    )
                }
            }
        }
        .padding(12)
        .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16))
    }

    private func byteLabel(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}

private struct CallsView: View {
    @ObservedObject var appState: MeshAppState
    @State private var showConfirm = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                HeaderTitle(title: "Звонки")
                    .frame(maxWidth: .infinity, alignment: .leading)

                ScrollView {
                    VStack(spacing: 8) {
                        ForEach(appState.callHistory) { call in
                            HStack(spacing: 12) {
                                Circle()
                                    .fill(Color.white.opacity(0.2))
                                    .frame(width: 48, height: 48)
                                    .overlay(Text(String(appState.displayName(for: call.peerId).prefix(1)).uppercased()).font(.headline.bold()))

                                VStack(alignment: .leading, spacing: 4) {
                                    Text(appState.displayName(for: call.peerId))
                                        .font(.headline)
                                    HStack(spacing: 6) {
                                        Image(systemName: call.outgoing ? "arrow.up.right" : "arrow.down.left")
                                            .foregroundStyle(call.outgoing ? .green : .red)
                                        Text(call.isVideo ? "Видео" : "Аудио")
                                            .font(.subheadline)
                                        Text(formatDate(call.startedAtMs))
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                            }
                            .padding(12)
                            .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
                        }

                        if appState.callHistory.isEmpty {
                            EmptyState(title: "История звонков пуста", subtitle: "Совершите вызов из чата", icon: "phone")
                        }
                    }
                }

                Button("Очистить историю") {
                    showConfirm = true
                }
                .foregroundStyle(.red)
                .padding(.bottom, 8)
            }
            .padding(16)
            .toolbar(.hidden, for: .navigationBar)
            .alert("Удалить историю звонков?", isPresented: $showConfirm) {
                Button("Удалить", role: .destructive) { appState.clearCallHistory() }
                Button("Отмена", role: .cancel) {}
            }
        }
    }
}

private struct SettingsView: View {
    @ObservedObject var appState: MeshAppState

    @State private var name = ""
    @FocusState private var nameFieldFocused: Bool

    @State private var showProfileSave = false
    @State private var showClearData = false
    @State private var showInfoNode = false
    @State private var showInfoTransport = false
    @State private var showInfoEncrypt = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    HeaderTitle(title: "Настройки")
                        .frame(maxWidth: .infinity, alignment: .leading)

                    profileCard
                    accountCard
                    networkCard
                    metricsCard
                    dataCard
                    logsCard
                }
                .padding(16)
            }
            .toolbar(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button {
                        nameFieldFocused = false
                    } label: {
                        Image(systemName: "keyboard.chevron.compact.down")
                    }
                }
            }
            .onAppear {
                name = appState.identity.nickname
            }
            .alert("Сохранить профиль", isPresented: $showProfileSave) {
                Button("Сохранить") { appState.saveProfile(name: name) }
                Button("Отмена", role: .cancel) {}
            }
            .alert("Очистить кэш и локальные данные?", isPresented: $showClearData) {
                Button("Очистить", role: .destructive) { appState.clearAllData() }
                Button("Отмена", role: .cancel) {}
            }
            .alert("Узлы сети", isPresented: $showInfoNode) {
                Button("ОК", role: .cancel) {}
            } message: {
                Text("Количество активных устройств, обнаруженных в LAN mesh.")
            }
            .alert("Транспорт", isPresented: $showInfoTransport) {
                Button("ОК", role: .cancel) {}
            } message: {
                Text("Discovery через UDP broadcast, обмен данными через P2P TCP-пакеты без сервера.")
            }
            .alert("Шифрование", isPresented: $showInfoEncrypt) {
                Button("ОК", role: .cancel) {}
            } message: {
                Text("Сообщения шифруются AES-GCM и подписываются ECDSA (P-256).")
            }
        }
    }

    private var profileCard: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(Color.white.opacity(0.2))
                .frame(width: 58, height: 58)
                .overlay(Text(String(appState.identity.nickname.prefix(1)).uppercased()).font(.title2.bold()))

            VStack(alignment: .leading, spacing: 4) {
                Text(appState.identity.nickname)
                    .font(.headline)
                Text(appState.peers.isEmpty ? "В сети (BLE/LAN: offline)" : "В сети (LAN active)")
                    .font(.caption)
                    .foregroundStyle(appState.peers.isEmpty ? .blue : .green)
            }
            Spacer()
        }
        .padding(14)
        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
    }

    private var accountCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Аккаунт").font(.headline)

            field("Имя", text: $name)

            Button("Сохранить изменения") { showProfileSave = true }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(Color.green.opacity(0.8), in: RoundedRectangle(cornerRadius: 10))
                .foregroundStyle(.white)
        }
        .padding(14)
        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
    }

    private var networkCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Сеть").font(.headline)

            infoRow(label: "Узлов в сети", value: "\(appState.peers.count)", infoAction: { showInfoNode = true })
            infoRow(label: "Тип подключения", value: "LAN Mesh", infoAction: { showInfoTransport = true })
            infoRow(label: "Шифрование", value: "Включено", infoAction: { showInfoEncrypt = true })
        }
        .padding(14)
        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
    }

    private var dataCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Данные").font(.headline)
            Text("Локально занято: ~\(estimatedSize)")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text("Очистка удалит историю чатов, звонков, групп и файловый кэш.")
                .font(.caption)
                .foregroundStyle(.secondary)
            Button("Очистить кэш") { showClearData = true }
                .foregroundStyle(.red)
        }
        .padding(14)
        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
    }

    private var metricsCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Метрики").font(.headline)
            metricRow(label: "RTT", value: formatMs(appState.rttMs))
            metricRow(label: "P95 RTT", value: formatMs(appState.p95Ms))
            metricRow(label: "Transferred data", value: formatBytes(appState.transferredBytes))
            Text("Сэмплов RTT: \(appState.rttSamplesCount)")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(14)
        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
    }

    private var logsCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Сетевой лог").font(.headline)
            ForEach(Array(appState.logs.suffix(8).enumerated()), id: \.offset) { _, line in
                Text(line)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if appState.logs.isEmpty {
                Text("Лог пуст")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(14)
        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
    }

    private var estimatedSize: String {
        let messages = appState.privateChats.values.reduce(0) { $0 + $1.count }
        let calls = appState.callHistory.count
        return "\((messages * 2 + calls) * 4) KB"
    }

    private func field(_ title: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            TextField(title, text: text)
                .padding(10)
                .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
                .focused($nameFieldFocused)
        }
    }

    private func metricRow(label: String, value: String) -> some View {
        HStack {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .font(.subheadline.weight(.semibold))
        }
        .padding(10)
        .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 10))
    }

    private func infoRow(label: String, value: String, infoAction: @escaping () -> Void) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(value)
                    .font(.subheadline.weight(.semibold))
                Text(label)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button(action: infoAction) {
                Image(systemName: "info.circle")
            }
        }
        .padding(10)
        .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 10))
    }

    private func formatMs(_ value: Double?) -> String {
        guard let value else { return "—" }
        return "\(Int(value.rounded())) мс"
    }

    private func formatBytes(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: max(0, bytes), countStyle: .binary)
    }
}

private struct IncomingCallOverlay: View {
    let call: ActiveCallState
    @ObservedObject var appState: MeshAppState

    var body: some View {
        Color.black.opacity(0.58)
            .ignoresSafeArea()
            .overlay {
                VStack(spacing: 18) {
                    Text("Входящий \(call.isVideo ? "видео" : "аудио") звонок")
                        .font(.headline)
                    Text(appState.displayName(for: call.peerId))
                        .font(.title3.bold())

                    HStack(spacing: 24) {
                        Button {
                            appState.declineIncomingCall()
                        } label: {
                            Image(systemName: "phone.down.fill")
                                .frame(width: 56, height: 56)
                                .background(Color.red, in: Circle())
                        }

                        Button {
                            appState.acceptIncomingCall()
                        } label: {
                            Image(systemName: "phone.fill")
                                .frame(width: 56, height: 56)
                                .background(Color.green, in: Circle())
                        }
                    }
                }
                .padding(22)
                .background(Color(red: 0.10, green: 0.12, blue: 0.18), in: RoundedRectangle(cornerRadius: 18))
                .padding(24)
            }
    }
}

private struct ActiveCallOverlay: View {
    let call: ActiveCallState
    @ObservedObject var appState: MeshAppState

    var body: some View {
        ZStack(alignment: .bottom) {
            if let remoteTrack = appState.remoteCallVideoTrack {
                RTCMetalVideoTrackView(track: remoteTrack)
                    .ignoresSafeArea()
            } else {
                Color.black.opacity(0.56)
                    .ignoresSafeArea()
                    .overlay(
                        Image(systemName: "person.crop.square")
                            .font(.system(size: 58, weight: .medium))
                            .foregroundStyle(.white.opacity(0.18))
                    )
            }

            if let localTrack = appState.localCallVideoTrack {
                RTCMetalVideoTrackView(track: localTrack)
                    .frame(width: 124, height: 170)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.white.opacity(0.34), lineWidth: 1))
                    .shadow(color: .black.opacity(0.28), radius: 8, y: 4)
                    .padding(.trailing, 14)
                    .padding(.bottom, 232)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }

            VStack(spacing: 12) {
                Text(isVideoMode ? "Видео звонок" : "Аудио звонок")
                    .font(.headline)
                Text(appState.displayName(for: call.peerId))
                    .font(.title3.bold())
                Text(callDuration)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                HStack(spacing: 20) {
                    callControl(
                        system: appState.isCallMicMuted ? "mic.slash.fill" : "mic.fill",
                        color: .orange,
                        isSelected: appState.isCallMicMuted
                    ) {
                        appState.toggleCallMicrophone()
                    }
                    callControl(
                        system: appState.isCallSpeakerEnabled ? "speaker.wave.2.fill" : "speaker.slash.fill",
                        color: .blue,
                        isSelected: appState.isCallSpeakerEnabled
                    ) {
                        appState.toggleCallSpeaker()
                    }
                    callControl(
                        system: appState.isCallVideoEnabled ? "video.fill" : "video.slash.fill",
                        color: .purple,
                        isSelected: appState.isCallVideoEnabled
                    ) {
                        appState.toggleCallVideo()
                    }
                }

                Button {
                    appState.endActiveCall()
                } label: {
                    Label("Завершить", systemImage: "phone.down.fill")
                        .padding(.horizontal, 22)
                        .padding(.vertical, 10)
                        .background(Color.red, in: Capsule())
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 16)
            .padding(.bottom, 18)
            .background(Color(red: 0.10, green: 0.12, blue: 0.18).opacity(0.86), in: RoundedRectangle(cornerRadius: 20))
            .padding(.horizontal, 16)
            .padding(.bottom, 22)
        }
    }

    private func callControl(system: String, color: Color, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: system)
                .frame(width: 44, height: 44)
                .background(color.opacity(isSelected ? 0.34 : 0.18), in: Circle())
                .overlay(
                    Circle()
                        .stroke(color.opacity(isSelected ? 0.95 : 0.38), lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }

    private var callDuration: String {
        let delta = max(0, Int64(Date().timeIntervalSince1970 * 1000) - call.startedAtMs)
        let sec = Int(delta / 1000)
        return String(format: "%02d:%02d", sec / 60, sec % 60)
    }

    private var isVideoMode: Bool {
        appState.isCallVideoEnabled || appState.remoteCallVideoTrack != nil || call.isVideo
    }
}

private struct RTCMetalVideoTrackView: UIViewRepresentable {
    let track: RTCVideoTrack

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> RTCMTLVideoView {
        let view = RTCMTLVideoView(frame: .zero)
        view.contentMode = .scaleAspectFill
        track.add(view)
        context.coordinator.currentTrack = track
        return view
    }

    func updateUIView(_ uiView: RTCMTLVideoView, context: Context) {
        if context.coordinator.currentTrack?.trackId != track.trackId {
            context.coordinator.currentTrack?.remove(uiView)
            track.add(uiView)
            context.coordinator.currentTrack = track
        }
    }

    static func dismantleUIView(_ uiView: RTCMTLVideoView, coordinator: Coordinator) {
        coordinator.currentTrack?.remove(uiView)
        coordinator.currentTrack = nil
    }

    final class Coordinator {
        var currentTrack: RTCVideoTrack?
    }
}

private struct EmptyState: View {
    let title: String
    let subtitle: String
    let icon: String

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(.secondary)
            Text(title)
                .font(.headline)
            Text(subtitle)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 14))
    }
}

private func formatShortTime(_ timestampMs: Int64) -> String {
    guard timestampMs > 0 else { return "--:--" }
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "ru_RU")
    formatter.dateFormat = "HH:mm"
    return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(timestampMs) / 1000))
}

private func formatDate(_ timestampMs: Int64) -> String {
    guard timestampMs > 0 else { return "--" }
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "ru_RU")
    formatter.dateFormat = "dd MMM, HH:mm"
    return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(timestampMs) / 1000))
}
