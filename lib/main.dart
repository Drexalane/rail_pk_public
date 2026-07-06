import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show EventChannel, MethodChannel;
import 'readme_content.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const RailPkApp());
}

class RailPkApp extends StatelessWidget {
  const RailPkApp({super.key});

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      debugShowCheckedModeBanner: false,
      home: HomeScreen(),
    );
  }
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  static const _channel = MethodChannel('com.drexalane.railpk/overlay');
  static const _updateChannel = MethodChannel('com.drexalane.railpk/update');
  static const _statusChannel =
      EventChannel('com.drexalane.railpk/overlay_status');

  bool _overlayRunning = false;
  bool _isLoading = false;
  int _selectedColor = 0xFFD900D0; // magenta par défaut
  String _version = '';

  static const _colors = [
    _ColorOption('Magenta', 0xFFD900D0),
    _ColorOption('Rouge', 0xFFFF0000),
    _ColorOption('Orange', 0xFFFF6D00),
    _ColorOption('Jaune', 0xFFFFD600),
    _ColorOption('Vert', 0xFF00C853),
    _ColorOption('Bleu', 0xFF2962FF),
    _ColorOption('Noir', 0xFF000000),
  ];

  @override
  void initState() {
    super.initState();
    _checkOverlayStatus();
    _listenOverlayStatus();
    _fetchVersion();
  }

  void _fetchVersion() async {
    try {
      final v = await _channel.invokeMethod<String>('getVersion');
      if (mounted && v != null) setState(() => _version = v);
    } catch (_) {}
  }

  void _listenOverlayStatus() {
    _statusChannel.receiveBroadcastStream().listen((event) {
      if (mounted && event is bool) {
        setState(() => _overlayRunning = event);
      }
    }, onError: (e) {
      debugPrint('Erreur flux status overlay: $e');
      setState(() => _overlayRunning = false);
    });
  }

  Future<void> _checkOverlayStatus() async {
    try {
      final running = await _channel.invokeMethod<bool>('isOverlayRunning');
      setState(() => _overlayRunning = running ?? false);
    } catch (e) {
      debugPrint('Erreur statut overlay: $e');
    }
  }

  Future<void> _setPkColor(int color) async {
    try {
      await _channel.invokeMethod('setPkColor', color);
      setState(() => _selectedColor = color);
    } catch (e) {
      debugPrint('Erreur couleur PK: $e');
    }
  }

  Future<void> _checkUpdate() async {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Vérification des mises à jour…')),
    );
    try {
      final info =
          await _updateChannel.invokeMethod<Map>('checkUpdate');
      if (info == null) return;
      final updateAvailable = info['updateAvailable'] as bool? ?? false;
      if (!mounted) return;
      if (updateAvailable) {
        final version = info['version'] as String? ?? '?';
        final size = (info['size'] as num?)?.toDouble() ?? 0;
        final sizeMb = (size / (1024 * 1024)).toStringAsFixed(1);
        final confirmed = await showDialog<bool>(
          context: context,
          builder: (ctx) => AlertDialog(
            title: const Text('Mise à jour disponible'),
            content: Text(
                'Version $version ($sizeMb Mo)\nTélécharger et installer ?'),
            actions: [
              TextButton(
                  onPressed: () => Navigator.pop(ctx, false),
                  child: const Text('Annuler')),
              FilledButton(
                  onPressed: () => Navigator.pop(ctx, true),
                  child: const Text('Installer')),
            ],
          ),
        );
        if (confirmed == true && info['downloadUrl'] is String) {
          await _downloadAndInstall(info['downloadUrl'] as String);
        }
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Aucune mise à jour disponible')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erreur: $e')),
        );
      }
    }
  }

  Future<void> _downloadAndInstall(String url) async {
    // Vérifie connectivité réseau avant téléchargement
    try {
      final hasNetwork =
          await _updateChannel.invokeMethod<bool>('hasNetwork');
      if (hasNetwork != true) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Aucune connexion Internet')),
          );
        }
        return;
      }
    } catch (_) {
      // Continue même si le check réseau échoue (fallback)
    }
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
          content: Text('Téléchargement en cours…'),
          duration: Duration(seconds: 30)),
    );
    try {
      final apkPath = await _updateChannel
          .invokeMethod<String>('downloadUpdate', {'url': url});
      if (apkPath == null) throw Exception('Téléchargement échoué');
      await _updateChannel.invokeMethod('installApk', {'path': apkPath});
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erreur téléchargement: $e')),
        );
      }
    }
  }

  Future<void> _centerOverlay() async {
    try {
      await _channel.invokeMethod('centerOverlay');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Fenêtre recentrée')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erreur: $e')),
        );
      }
    }
  }

  Future<void> _toggleOverlay() async {
    if (_isLoading) return;
    setState(() => _isLoading = true);
    try {
      if (_overlayRunning) {
        await _channel.invokeMethod('stopOverlay');
        setState(() { _overlayRunning = false; _isLoading = false; });
      } else {
        final hasAllPerm =
            await _channel.invokeMethod<bool>('hasAllPermissions');
        if (hasAllPerm != true) {
          await _channel.invokeMethod('requestPermissions');
          // Relance automatique après acceptation des permissions
          final granted =
              await _channel.invokeMethod<bool>('hasAllPermissions');
          if (granted == true) {
            // Vérifie l'état du GPS
            final gpsOk = await _channel.invokeMethod<bool>('isGpsEnabled') ?? true;
            if (!gpsOk && mounted) {
              setState(() => _isLoading = false);
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('Active le GPS dans les paramètres Android')),
              );
              return;
            }
            await _channel.invokeMethod('startOverlay');
            setState(() => _overlayRunning = true);
          } else {
            if (mounted) {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('Permissions refusées')),
              );
            }
          }
          setState(() => _isLoading = false);
          return;
        }
        // Vérifie état GPS
        final gpsOk = await _channel.invokeMethod<bool>('isGpsEnabled') ?? true;
        if (!gpsOk && mounted) {
          setState(() => _isLoading = false);
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Active le GPS dans les paramètres Android')),
          );
          return;
        }
        await _channel.invokeMethod('startOverlay');
        setState(() { _overlayRunning = true; _isLoading = false; });
      }
    } catch (e) {
      setState(() => _isLoading = false);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erreur: $e')),
        );
      }
    }
  }

  void _showReadme() {
    showDialog(
      context: context,
      builder: (_) => Dialog(
        insetPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 32),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        child: Stack(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 48, 16, 16),
              child: SingleChildScrollView(
                child: SelectableText(
                  readmeContent,
                  style: const TextStyle(
                    fontSize: 13,
                    height: 1.5,
                    color: Colors.black87,
                  ),
                ),
              ),
            ),
            Positioned(
              top: 8,
              right: 8,
              child: IconButton(
                icon: const Icon(Icons.close),
                onPressed: () => Navigator.of(context).pop(),
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              const SizedBox(height: 8),
              const Text(
                'Rail PK',
                style: TextStyle(fontSize: 28, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 20),
              ElevatedButton.icon(
                onPressed: _isLoading ? null : _toggleOverlay,
                icon: _isLoading
                    ? const SizedBox(
                        width: 20, height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white54))
                    : Icon(_overlayRunning ? Icons.stop : Icons.play_arrow),
                label: Text(_isLoading ? 'Patientez…' : (_overlayRunning ? 'Arrêter' : 'Démarrer')),
                style: ElevatedButton.styleFrom(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 32, vertical: 12),
                ),
              ),
              const SizedBox(height: 24),
              _buildColorPicker(),
              const SizedBox(height: 24),
              OutlinedButton(
                onPressed: _centerOverlay,
                style: OutlinedButton.styleFrom(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 24, vertical: 10),
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: const [
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.center_focus_strong, size: 18),
                        SizedBox(width: 8),
                        Text('Centrer la fenêtre'),
                      ],
                    ),
                    SizedBox(height: 2),
                    Text(
                      'Si la fenêtre sort de l\'écran',
                      style: TextStyle(fontSize: 11, color: Colors.grey),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              OutlinedButton.icon(
                onPressed: _checkUpdate,
                icon: const Icon(Icons.system_update, size: 18),
                label: const Text('Vérifier les mises à jour'),
                style: OutlinedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(
                      horizontal: 24, vertical: 10),
                ),
              ),
              const SizedBox(height: 16),
              TextButton.icon(
                onPressed: _showReadme,
                icon: const Icon(Icons.info_outline, size: 18),
                label: const Text('README'),
              ),
              const SizedBox(height: 16),
              _buildFooter(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildColorPicker() {
    return Column(
      children: [
        const Text(
          'Couleur du PK',
          style: TextStyle(fontSize: 13, color: Colors.black54),
        ),
        const SizedBox(height: 8),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: _colors.map((c) {
            final selected = _selectedColor == c.value;
            return Padding(
              padding: const EdgeInsets.symmetric(horizontal: 6),
              child: GestureDetector(
                onTap: () => _setPkColor(c.value),
                child: Container(
                  width: 36,
                  height: 36,
                  decoration: BoxDecoration(
                    color: Color(c.value),
                    shape: BoxShape.circle,
                    border: Border.all(
                      color: selected ? Colors.black : Colors.grey.shade400,
                      width: selected ? 3 : 1,
                    ),
                    boxShadow: selected
                        ? [
                            BoxShadow(
                              color: Color(c.value).withAlpha(80),
                              blurRadius: 8,
                              spreadRadius: 1,
                            )
                          ]
                        : null,
                  ),
                ),
              ),
            );
          }).toList(),
        ),
      ],
    );
  }

  Widget _buildFooter() {
    final versionText = _version.isNotEmpty ? 'Version $_version' : '';
    return Column(
      children: [
        if (versionText.isNotEmpty)
          Text(
            versionText,
            style: const TextStyle(fontSize: 11, color: Colors.grey),
          ),
        const SizedBox(height: 2),
        const Text(
          'Développé par Alexandre D.',
          style: TextStyle(fontSize: 11, color: Colors.grey),
        ),
      ],
    );
  }
}

class _ColorOption {
  final String name;
  final int value;
  const _ColorOption(this.name, this.value);
}
