const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const Bonjour = require('bonjour-service');

const bonjour = new Bonjour.Bonjour();
let mainWindow;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 800,
    height: 600,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      nodeIntegration: false,
      contextIsolation: true
    },
    autoHideMenuBar: true,
    titleBarStyle: 'hidden',
    titleBarOverlay: {
      color: '#1e1e24',
      symbolColor: '#ffffff'
    }
  });

  mainWindow.loadFile('index.html');
}

app.whenReady().then(() => {
  createWindow();

  app.on('activate', function () {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });

  startDiscovery();
});

app.on('window-all-closed', function () {
  if (process.platform !== 'darwin') app.quit();
});

// Service Discovery
let discoveredReceivers = [];

function startDiscovery() {
  // Listen for _screenmirror._tcp
  const browser = bonjour.find({ type: 'screenmirror' }, function (service) {
    console.log('Found an HTTP server:', service.name);
    // Add to discovered
    const receiver = {
      name: service.name,
      ip: service.addresses ? service.addresses[0] : service.host,
      port: service.port
    };
    
    // Check if we already have it
    const exists = discoveredReceivers.find(r => r.ip === receiver.ip);
    if (!exists) {
      discoveredReceivers.push(receiver);
      // Notify renderer
      if (mainWindow) {
        mainWindow.webContents.send('receivers-updated', discoveredReceivers);
      }
    }
  });

  // Also handle when services go down
  browser.on('down', (service) => {
    console.log('Service down:', service.name);
    discoveredReceivers = discoveredReceivers.filter(r => r.name !== service.name);
    if (mainWindow) {
      mainWindow.webContents.send('receivers-updated', discoveredReceivers);
    }
  });
}

// IPC handlers
ipcMain.handle('get-receivers', () => {
  return discoveredReceivers;
});
