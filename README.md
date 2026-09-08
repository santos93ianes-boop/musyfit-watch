# MusyFit Watch 3.0

Aplicativo Android companheiro para personalização e diagnóstico de smartwatches, com foco inicial em:
- Redmi Watch 5 Active
- T800 Ultra2

## Entregue nesta versão
- 10 temas oficiais MusyFit: Tech Future, Luxury Black, Neon Cyber, Sport Pro, Racing, Tactical, Minimal, Space, Executive e Minha Foto.
- Pré-visualização em 320x385.
- Editor Minha Foto com zoom, posição, tamanho e estilo de hora.
- Exportação PNG para `Fotos/MusyFit Watch`.
- Busca BLE multi-relógio.
- Validação real GATT: o app só mostra conectado após descoberta de serviços.
- Leitura do serviço padrão de bateria quando o relógio o expõe.
- Diagnóstico de serviços/características GATT, incluindo canais graváveis e notificáveis.
- QR scanner + gerador de QR.
- Atalhos para Mi Fitness (Redmi) e HIwatch Pro (T800 Ultra2).

## Limite técnico importante
Conectar por Bluetooth não autoriza automaticamente a transferência de um mostrador. Redmi Watch 5 Active e T800 Ultra2 usam protocolos de transferência específicos do fabricante/firmware. Esta versão não envia bytes arbitrários para características desconhecidas, evitando travamentos ou corrupção do relógio. Ela prepara/exporta o tema, valida a conexão e mostra os canais disponíveis para o próximo estágio de compatibilidade.

## GitHub Actions
Use `.github/workflows/android.yml` ou o arquivo `android-v3.yml` fornecido separadamente. O APK debug aparecerá em Artifacts.
