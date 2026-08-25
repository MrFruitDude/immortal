/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import SwiftUI

/// USB provisioning workspace: prerequisites, both modes, and step progress.
struct ProvisioningView: View {
    @EnvironmentObject var store: PortalManagerStore

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                header
                modeCards
                noDownloadCard
                Spacer(minLength: 24)
            }
            .padding(.horizontal, 34)
            .padding(.top, 22)
        }
        .navigationTitle("")
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Provisioning")
                .font(.pmDisplay(26))
            Text("Two separate local flows — enable an existing Immortal install, or provision a new device from a verified artifact.")
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
        }
    }

    private var modeCards: some View {
        HStack(alignment: .top, spacing: 18) {
            GlassCard(padding: 26) {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 12) {
                        GradientIcon(systemName: "arrow.triangle.2.circlepath.circle", size: 42)
                        Text("Enablement / Recovery")
                            .font(.system(size: 15.5, weight: .semibold))
                    }
                    Text("For an already installed, compatible Immortal app. Writes the provision.json handoff, relaunches Immortal, and reads the generated agent.json — no APK is installed.")
                        .font(.system(size: 12.5))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)

                    stepsPreview(steps: [
                        "Preflight over authorized ADB",
                        "Write provision.json",
                        "Relaunch Immortal",
                        "Read agent.json + verify bearer /info",
                    ])

                    PrimaryButton(title: "Start Enablement", systemImage: "bolt.horizontal", disabled: true) {}
                    Text("Connect a Portal over USB to begin.")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.tertiary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            GlassCard(padding: 26) {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 12) {
                        GradientIcon(
                            systemName: "shippingbox.and.arrow.backward",
                            size: 42,
                            colors: [PortalTheme.warm, PortalTheme.warning]
                        )
                        Text("Full USB Provisioning")
                            .font(.system(size: 15.5, weight: .semibold))
                    }
                    Text("Installs and configures Immortal from an operator-selected local artifact. Identity, signature, digest, API level, ABI, and model compatibility are all verified before any install command runs.")
                        .font(.system(size: 12.5))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)

                    stepsPreview(steps: [
                        "Select local APK (no downloads)",
                        "Verify identity/signature/digest/API/ABI/model",
                        "Device setup + installation",
                        "Enablement as a distinct final phase",
                    ])

                    GhostButton(title: "Choose Artifact…", systemImage: "folder", disabled: true) {}
                    Text("Connect a Portal over USB to begin.")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.tertiary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private func stepsPreview(steps: [String]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                HStack(spacing: 10) {
                    Text("\(index + 1)")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(PortalTheme.blue)
                        .frame(width: 20, height: 20)
                        .background(Circle().fill(PortalTheme.blueWash))
                        .foregroundStyle(.white)
                    Text(step)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var noDownloadCard: some View {
        GlassCard(padding: 22) {
            HStack(spacing: 14) {
                GradientIcon(
                    systemName: "tray.and.arrow.down.fill",
                    size: 38,
                    colors: [.gray.opacity(0.75), .gray.opacity(0.5)]
                )
                VStack(alignment: .leading, spacing: 4) {
                    Text("Zero downloads, ever")
                        .font(.system(size: 13.5, weight: .semibold))
                    Text("Platform tools, APKs, packages, and release artifacts are never fetched from the network. You provide ADB locally; artifacts come from your own disk.")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
            }
        }
    }
}
