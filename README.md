# MusyFit Watch V2.1

Aplicativo Android companheiro para o Redmi Watch 5 Active.

## Novidades da V2.1
- 10 temas MusyFit.
- Minha Foto com pré-visualização.
- Busca de dispositivos Bluetooth Low Energy (BLE).
- Permissões Bluetooth para Android 12+ e compatibilidade com Android 8+.
- Conexão GATT ao relógio e descoberta dos serviços/characteristics disponíveis.
- Tela de status de conexão em tempo real.
- Estrutura pronta para implementar o transporte de mostradores quando o protocolo compatível for identificado.

## Importante sobre sincronização
O Redmi Watch 5 Active usa o Mi Fitness para pareamento e sincronização oficial. Esta V2.1 cria uma conexão BLE própria e descobre os serviços expostos pelo relógio, mas **não envia um watch face por um UUID inventado**. O envio direto será implementado somente depois que o serviço, as characteristics, autenticação e formato do pacote forem validados no dispositivo real.

## Teste no relógio
1. Instale o APK no celular Android.
2. Deixe o Redmi Watch 5 Active ligado e próximo.
3. Abra MusyFit Watch > Conectar ao relógio.
4. Autorize Bluetooth.
5. Toque em Buscar relógio.
6. Selecione o Redmi Watch encontrado e toque em Conectar.
7. O app mostrará a quantidade de serviços BLE descobertos.

## GitHub Actions
Suba a pasta inteira para o GitHub e execute **Build MusyFit Watch APK** em Actions. O APK fica no artifact `MusyFit-Watch-APK`.


## QR Code
- Leitor QR usando a câmera do celular.
- Gerador QR para links/códigos do MusyFit.
- Pode ler QR exibido pelo relógio para análise/importação futura.
- O Redmi Watch 5 Active não aceita instalação de APK Android por QR; o recurso não simula essa capacidade.

## V2.2 — Ícone oficial
- Ícone oficial MusyFit Watch incluído no APK.
- Recursos mipmap para mdpi, hdpi, xhdpi, xxhdpi e xxxhdpi.
- Ícone redondo configurado no AndroidManifest.
- Mantém Bluetooth BLE, QR Code, 10 temas, Minha Foto e pré-visualização da V2.1.
