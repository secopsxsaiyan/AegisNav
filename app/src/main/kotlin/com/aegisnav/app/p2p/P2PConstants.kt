package com.aegisnav.app.p2p

/**
 * P2PConstants — centralized relay URL constants (Finding 3.2).
 *
 * Moved from P2PSetupScreen.kt to avoid hardcoded URLs scattered in UI code.
 * Used by P2PManager and P2PSetupScreen.
 */
const val DEFAULT_RELAY_URL   = "wss://relay.aegisnav.com/socket.io/?EIO=4&transport=websocket"
const val DEFAULT_RELAY_URL_2 = "wss://relay.aegisnav.online/socket.io/?EIO=4&transport=websocket"
