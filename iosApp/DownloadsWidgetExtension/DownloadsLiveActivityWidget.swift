import ActivityKit
import SwiftUI
import WidgetKit

struct DownloadsLiveActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        let status: String
        let progressPercent: Int
        let transferredText: String
    }

    let downloadId: String
    let title: String
    let subtitle: String
    let posterUrl: String?
}

@available(iOSApplicationExtension 16.1, *)
struct DownloadsLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: DownloadsLiveActivityAttributes.self) { context in
            DownloadActivityLockScreenView(context: context)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    PosterThumbnailView(urlString: context.attributes.posterUrl)
                        .frame(width: 44, height: 64)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    VStack(alignment: .trailing, spacing: 3) {
                        Text(progressLabel(context.state.progressPercent))
                            .font(.headline.monospacedDigit())
                            .foregroundStyle(.primary)
                        Text(statusLabel(context.state.status))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(context.attributes.title)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                        Text(context.attributes.subtitle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                        ProgressView(value: normalizedProgress(context.state.progressPercent))
                            .progressViewStyle(.linear)
                        HStack {
                            Text(context.state.transferredText)
                                .font(.caption2.monospacedDigit())
                                .foregroundStyle(.secondary)
                            Spacer(minLength: 6)
                            Text(statusLabel(context.state.status))
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            } compactLeading: {
                PosterGlyphView()
            } compactTrailing: {
                Text(progressLabel(context.state.progressPercent))
                    .font(.caption2.monospacedDigit())
            } minimal: {
                PosterGlyphView()
            }
        }
    }

    private func progressLabel(_ progressPercent: Int) -> String {
        if progressPercent < 0 { return "--%" }
        return "\(max(0, min(100, progressPercent)))%"
    }

    private func normalizedProgress(_ progressPercent: Int) -> Double {
        guard progressPercent >= 0 else { return 0 }
        return min(max(Double(progressPercent) / 100.0, 0), 1)
    }

    private func statusLabel(_ status: String) -> String {
        switch status.lowercased() {
        case "downloading": return "Downloading"
        case "paused": return "Paused"
        case "failed": return "Failed"
        default: return "Active"
        }
    }
}

@available(iOSApplicationExtension 16.1, *)
private struct DownloadActivityLockScreenView: View {
    let context: ActivityViewContext<DownloadsLiveActivityAttributes>

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            PosterThumbnailView(urlString: context.attributes.posterUrl)
                .frame(width: 62, height: 92)

            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(context.attributes.title)
                        .font(.headline)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    Text(progressLabel(context.state.progressPercent))
                        .font(.headline.monospacedDigit())
                }
                Text(context.attributes.subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                ProgressView(value: normalizedProgress(context.state.progressPercent))
                    .progressViewStyle(.linear)
                HStack {
                    Text(statusLabel(context.state.status))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text(context.state.transferredText)
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 6)
        .activityBackgroundTint(Color(red: 0.10, green: 0.11, blue: 0.16).opacity(0.88))
        .activitySystemActionForegroundColor(.white)
    }

    private func progressLabel(_ progressPercent: Int) -> String {
        if progressPercent < 0 { return "--%" }
        return "\(max(0, min(100, progressPercent)))%"
    }

    private func normalizedProgress(_ progressPercent: Int) -> Double {
        guard progressPercent >= 0 else { return 0 }
        return min(max(Double(progressPercent) / 100.0, 0), 1)
    }

    private func statusLabel(_ status: String) -> String {
        switch status.lowercased() {
        case "downloading": return "Downloading"
        case "paused": return "Paused"
        case "failed": return "Failed"
        default: return "Active"
        }
    }
}

private struct PosterGlyphView: View {
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(Color.white.opacity(0.14))
            Image(systemName: "film")
                .font(.caption)
                .foregroundStyle(.white.opacity(0.9))
        }
        .frame(width: 22, height: 22)
    }
}

private struct PosterThumbnailView: View {
    let urlString: String?

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(
                    LinearGradient(
                        colors: [Color(red: 0.16, green: 0.17, blue: 0.22), Color(red: 0.09, green: 0.10, blue: 0.14)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing,
                    ),
                )

            if let url = imageUrl {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .empty:
                        ProgressView()
                            .tint(.white.opacity(0.85))
                    case .success(let image):
                        image
                            .resizable()
                            .scaledToFill()
                    case .failure:
                        fallbackIcon
                    @unknown default:
                        fallbackIcon
                    }
                }
            } else {
                fallbackIcon
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .stroke(Color.white.opacity(0.12), lineWidth: 1),
        )
        .shadow(color: .black.opacity(0.24), radius: 6, x: 0, y: 3)
    }

    private var imageUrl: URL? {
        guard let urlString, let url = URL(string: urlString), !urlString.isEmpty else {
            return nil
        }
        return url
    }

    private var fallbackIcon: some View {
        Image(systemName: "film")
            .font(.title3)
            .foregroundStyle(.white.opacity(0.82))
    }
}
