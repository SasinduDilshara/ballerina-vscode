// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com/) All Rights Reserved.

// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at

// http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

/**
 * System-prompt rule against performing I/O inside a lock, with the fan-out pattern that replaces it.
 *
 * Generated WebSocket broadcasts held one lock over the subscriber map for the whole loop and wrote to
 * every connection inside it ("the simplest fix: do the entire broadcast within a single lock block"), so
 * one slow or dead client blocked delivery to every other subscriber. The obvious repair — snapshot the
 * callers into an array inside the lock — does NOT compile: a lock over an isolated root may transfer out
 * only an isolated expression, and while a single `websocket:Caller` is an isolated object, an array of them
 * is not (BCE3959), and `.clone()` is not applicable to objects. The pattern below is the one that compiles
 * (verified against Ballerina 2201.13.4): copy the keys, fetch one caller per short lock, write outside.
 *
 * Kept in its own module (no imports) so it can be unit-tested without loading the extension host.
 */
export const LOCK_IO_RULE = `Never perform network I/O or remote calls (\`->writeMessage\`, HTTP/DB client calls, \`runtime:sleep\`) inside a \`lock\`: one slow or dead peer then blocks every other caller of that lock. A lock over an isolated root (an \`isolated\` variable or \`self\` of an isolated object) may transfer OUT only an isolated expression — a \`readonly\` value or a SINGLE isolated object such as one \`websocket:Caller\`. An ARRAY or MAP of callers is NOT isolated: \`lock { targets = subscribers.toArray(); }\` fails to compile (BCE3959), and \`.clone()\` does not apply to objects. Fan-out pattern — snapshot the KEYS, fetch one connection per short lock, write OUTSIDE the lock, clean up in another short lock:
\`\`\`ballerina
isolated map<websocket:Caller> subscribers = {};

isolated function broadcast(readonly & OrderUpdate event) {
    string[] connectionIds;
    lock {
        connectionIds = subscribers.keys().clone(); // string[] is mutable, so .clone() is required
    }
    foreach string connectionId in connectionIds {
        websocket:Caller? caller;
        lock {
            caller = subscribers[connectionId]; // a single isolated object leaves the lock as-is
        }
        if caller is () {
            continue;
        }
        websocket:Error? result = caller->writeMessage(event); // I/O OUTSIDE the lock
        if result is websocket:Error {
            lock {
                _ = subscribers.removeIfHasKey(connectionId); // cleanup in its own short lock
            }
        }
    }
}
\`\`\`
Build the value being sent (\`event\`) before the loop, outside every lock, and pass it as \`readonly\` so it can enter the locks freely.`;
