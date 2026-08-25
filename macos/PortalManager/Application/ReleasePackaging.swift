/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// A signed/notarized macOS candidate selected by the release workflow.
struct ReleaseCandidate: Equatable, Sendable {
    var version: String
    var appPath: String
    var notarizationTicketPath: String?
}

enum ReleasePackagingFailure: Error, Equatable, Sendable {
    case appNotReadable
    case signatureMissing
    case ticketMissingOrInvalid
}

protocol ReleasePackagingVerifier: Sendable {
    func verify(_ candidate: ReleaseCandidate) async throws
}

struct SystemReleasePackagingVerifier: ReleasePackagingVerifier {
    func verify(_ candidate: ReleaseCandidate) async throws {
        var isDirectory: ObjCBool = false
        let fileManager = FileManager.default
        guard !candidate.appPath.isEmpty,
              fileManager.fileExists(atPath: candidate.appPath, isDirectory: &isDirectory),
              isDirectory.boolValue else {
            throw ReleasePackagingFailure.appNotReadable
        }

        let codesign = Process()
        codesign.executableURL = URL(fileURLWithPath: "/usr/bin/codesign")
        codesign.arguments = ["--verify", "--strict", "--verbose=2", candidate.appPath]
        try codesign.run()
        codesign.waitUntilExit()
        guard codesign.terminationStatus == 0 else {
            throw ReleasePackagingFailure.signatureMissing
        }

        if let ticketPath = candidate.notarizationTicketPath {
            var unused: ObjCBool = false
            guard fileManager.fileExists(atPath: ticketPath, isDirectory: &unused) else {
                throw ReleasePackagingFailure.ticketMissingOrInvalid
            }
            let stapler = Process()
            stapler.executableURL = URL(fileURLWithPath: "/usr/bin/stapler")
            stapler.arguments = ["validate", candidate.appPath]
            try stapler.run()
            stapler.waitUntilExit()
            guard stapler.terminationStatus == 0 else {
                throw ReleasePackagingFailure.ticketMissingOrInvalid
            }
        } else {
            throw ReleasePackagingFailure.ticketMissingOrInvalid
        }
    }
}
