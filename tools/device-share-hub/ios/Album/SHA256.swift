import Foundation

enum SHA256 {
    private static let initial: [UInt32] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    ]

    private static let constants: [UInt32] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    ]

    static func fileHex(_ url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { handle.closeFile() }
        var state = State()
        while true {
            let data = handle.readData(ofLength: 1024 * 1024)
            if data.isEmpty { break }
            state.update(data)
        }
        return state.finalize().map { String(format: "%02x", $0) }.joined()
    }

    struct State {
        private var hash = SHA256.initial
        private var buffer = Data()
        private var byteCount: UInt64 = 0

        init() {}

        mutating func update(_ data: Data) {
            byteCount += UInt64(data.count)
            buffer.append(data)
            while buffer.count >= 64 {
                process(buffer.prefix(64))
                buffer.removeFirst(64)
            }
        }

        mutating func finalize() -> [UInt8] {
            let bitCount = byteCount * 8
            buffer.append(0x80)
            while buffer.count % 64 != 56 { buffer.append(0) }
            for shift in stride(from: 56, through: 0, by: -8) {
                buffer.append(UInt8((bitCount >> UInt64(shift)) & 0xff))
            }
            while !buffer.isEmpty {
                process(buffer.prefix(64))
                buffer.removeFirst(64)
            }
            return hash.flatMap { value in
                [UInt8(truncatingIfNeeded: value >> 24), UInt8(truncatingIfNeeded: value >> 16),
                 UInt8(truncatingIfNeeded: value >> 8), UInt8(truncatingIfNeeded: value)]
            }
        }

        private mutating func process(_ block: Data.SubSequence) {
            let bytes = Array(block)
            var words = Array(repeating: UInt32(0), count: 64)
            for index in 0..<16 {
                let offset = index * 4
                words[index] = UInt32(bytes[offset]) << 24 | UInt32(bytes[offset + 1]) << 16 |
                    UInt32(bytes[offset + 2]) << 8 | UInt32(bytes[offset + 3])
            }
            for index in 16..<64 {
                let x = words[index - 15]
                let y = words[index - 2]
                let s0 = rotateRight(x, 7) ^ rotateRight(x, 18) ^ (x >> 3)
                let s1 = rotateRight(y, 17) ^ rotateRight(y, 19) ^ (y >> 10)
                words[index] = words[index - 16] &+ s0 &+ words[index - 7] &+ s1
            }
            var a = hash[0], b = hash[1], c = hash[2], d = hash[3]
            var e = hash[4], f = hash[5], g = hash[6], h = hash[7]
            for index in 0..<64 {
                let sum1 = rotateRight(e, 6) ^ rotateRight(e, 11) ^ rotateRight(e, 25)
                let choose = (e & f) ^ (~e & g)
                let temp1 = h &+ sum1 &+ choose &+ SHA256.constants[index] &+ words[index]
                let sum0 = rotateRight(a, 2) ^ rotateRight(a, 13) ^ rotateRight(a, 22)
                let majority = (a & b) ^ (a & c) ^ (b & c)
                let temp2 = sum0 &+ majority
                h = g; g = f; f = e; e = d &+ temp1
                d = c; c = b; b = a; a = temp1 &+ temp2
            }
            hash[0] &+= a; hash[1] &+= b; hash[2] &+= c; hash[3] &+= d
            hash[4] &+= e; hash[5] &+= f; hash[6] &+= g; hash[7] &+= h
        }

        private func rotateRight(_ value: UInt32, _ count: UInt32) -> UInt32 {
            return (value >> count) | (value << (32 - count))
        }
    }
}
