let selectedReceiver = null;
let ws = null;
let peerConnection = null;
let localStream = null;

// UI Elements
const receiverList = document.getElementById('receiverList');
const statusBadge = document.getElementById('statusBadge');
const startBtn = document.getElementById('startBtn');
const stopBtn = document.getElementById('stopBtn');
const bitrateRange = document.getElementById('bitrateRange');
const bitrateValue = document.getElementById('bitrateValue');

// Update Bitrate UI
bitrateRange.addEventListener('input', (e) => {
    bitrateValue.textContent = e.target.value;
    if (peerConnection) {
        changeBitrate(parseInt(e.target.value));
    }
});

// Layout Mode Change listener
document.querySelectorAll('input[name="layoutMode"]').forEach(radio => {
    radio.addEventListener('change', (e) => {
        if (ws && ws.readyState === WebSocket.OPEN) {
            sendLayout(e.target.value);
        }
    });
});

// IPC: Listen for discovered receivers
window.electronAPI.onReceiversUpdated((receivers) => {
    receiverList.innerHTML = '';
    
    if (receivers.length === 0) {
        receiverList.innerHTML = `
            <div class="empty-state">
                <div class="spinner"></div>
                <p>Looking for TVs on local network...</p>
            </div>
        `;
        selectedReceiver = null;
        updateStartButton();
        return;
    }

    receivers.forEach(receiver => {
        const div = document.createElement('div');
        div.className = 'receiver-item';
        if (selectedReceiver && selectedReceiver.ip === receiver.ip) {
            div.classList.add('selected');
        }
        
        div.innerHTML = `
            <div class="receiver-info">
                <h3>${receiver.name}</h3>
                <p>${receiver.ip}:${receiver.port}</p>
            </div>
        `;
        
        div.addEventListener('click', () => {
            document.querySelectorAll('.receiver-item').forEach(el => el.classList.remove('selected'));
            div.classList.add('selected');
            selectedReceiver = receiver;
            updateStartButton();
        });
        
        receiverList.appendChild(div);
    });
});

function updateStartButton() {
    startBtn.disabled = !selectedReceiver;
}

// Start Streaming
startBtn.addEventListener('click', async () => {
    if (!selectedReceiver) return;
    
    try {
        const fps = parseInt(document.querySelector('input[name="fps"]:checked').value);
        const resolution = document.querySelector('input[name="resolution"]:checked').value;
        const maxH = resolution === '1080p' ? 1080 : 720;
        
        // Capture screen
        localStream = await navigator.mediaDevices.getDisplayMedia({
            video: {
                frameRate: { ideal: fps, max: fps },
                height: { max: maxH }
            },
            audio: false // Mirroring Android streamer behavior
        });

        // The hidden video element to keep the stream active
        document.getElementById('localVideo').srcObject = localStream;

        // Listen for user stopping stream from Chrome UI
        localStream.getVideoTracks()[0].addEventListener('ended', stopStreaming);

        connectWebSocket();
        
        startBtn.classList.add('hidden');
        stopBtn.classList.remove('hidden');
        statusBadge.textContent = 'Streaming';
        statusBadge.classList.add('connected');
        
    } catch (err) {
        console.error('Error capturing screen:', err);
        alert('Could not capture screen: ' + err.message);
    }
});

// Stop Streaming
stopBtn.addEventListener('click', stopStreaming);

function stopStreaming() {
    if (localStream) {
        localStream.getTracks().forEach(track => track.stop());
        localStream = null;
    }
    if (peerConnection) {
        peerConnection.close();
        peerConnection = null;
    }
    if (ws) {
        ws.close();
        ws = null;
    }
    
    startBtn.classList.remove('hidden');
    stopBtn.classList.add('hidden');
    statusBadge.textContent = 'Searching...';
    statusBadge.classList.remove('connected');
}

function connectWebSocket() {
    ws = new WebSocket(`ws://${selectedReceiver.ip}:${selectedReceiver.port}`);
    
    ws.onopen = () => {
        console.log('Connected to signaling server');
        setupWebRTC();
    };
    
    ws.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        console.log('Got msg:', msg.type);
        
        if (msg.type === 'answer' && peerConnection) {
            peerConnection.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: msg.sdp }));
        } else if (msg.type === 'candidate' && peerConnection) {
            peerConnection.addIceCandidate(new RTCIceCandidate({
                sdpMid: msg.sdpMid,
                sdpMLineIndex: msg.sdpMLineIndex,
                candidate: msg.candidate
            }));
        }
    };
    
    ws.onerror = (err) => {
        console.error('WebSocket error:', err);
    };
    
    ws.onclose = () => {
        console.log('WebSocket closed');
        stopStreaming();
    };
}

async function setupWebRTC() {
    peerConnection = new RTCPeerConnection({
        iceServers: []
    });

    peerConnection.onicecandidate = (event) => {
        if (event.candidate) {
            sendSignalingMsg({
                type: 'candidate',
                sdpMid: event.candidate.sdpMid,
                sdpMLineIndex: event.candidate.sdpMLineIndex,
                candidate: event.candidate.candidate
            });
        }
    };

    localStream.getTracks().forEach(track => {
        peerConnection.addTrack(track, localStream);
    });

    // Enforce initial bitrate
    changeBitrate(parseInt(bitrateRange.value));

    const offer = await peerConnection.createOffer();
    
    // Simple SDP munging to enforce bitrate on Windows Electron
    let sdp = offer.sdp;
    sdp = enforceBitrateSDP(sdp, parseInt(bitrateRange.value));
    
    await peerConnection.setLocalDescription({ type: 'offer', sdp: sdp });

    sendSignalingMsg({
        type: 'offer',
        sdp: peerConnection.localDescription.sdp
    });

    // Send initial layout
    const layout = document.querySelector('input[name="layoutMode"]:checked').value;
    sendLayout(layout);
}

function sendSignalingMsg(msg) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(msg));
    }
}

function sendLayout(mode) {
    sendSignalingMsg({
        type: 'layout',
        candidate: mode
    });
}

function changeBitrate(kbps) {
    if (!peerConnection) return;
    
    const senders = peerConnection.getSenders();
    const sender = senders.find(s => s.track && s.track.kind === 'video');
    
    if (sender) {
        const parameters = sender.getParameters();
        if (!parameters.encodings) {
            parameters.encodings = [{}];
        }
        parameters.encodings[0].maxBitrate = kbps * 1000;
        
        sender.setParameters(parameters)
            .then(() => console.log(`Bitrate set to ${kbps} kbps`))
            .catch(err => console.error('Failed to set bitrate', err));
    }
}

function enforceBitrateSDP(sdp, kbps) {
    const lines = sdp.split('\r\n');
    const mLineIndex = lines.findIndex(line => line.startsWith('m=video'));
    if (mLineIndex === -1) return sdp;

    lines.splice(mLineIndex + 1, 0, `b=AS:${kbps}`);
    return lines.join('\r\n');
}

// Request initial receivers on load
window.electronAPI.getReceivers().then(receivers => {
    // Manually trigger the listener the first time just in case some were found before UI loaded
    if(receivers && receivers.length > 0) {
        window.dispatchEvent(new CustomEvent('initial-receivers', { detail: receivers }));
    }
});
