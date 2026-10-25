# Como publicar uma versão

Uma versão sai para as três plataformas, com o mesmo número:

- **iOS:** a tag do GitHub já é a versão que o Swift Package Manager enxerga.
- **Android:** o workflow [`release.yml`](.github/workflows/release.yml) sobe o pacote para o Maven Central.
- **React Native:** o workflow [`npm-publish.yml`](.github/workflows/npm-publish.yml), rodado à mão depois do Maven Central (passo 5).

> **No Maven Central nada se apaga nem se substitui.** Versão publicada com erro só se corrige com a próxima versão. Por isso o pacote sobe e fica esperando a sua confirmação no portal (passo 4).

## 1. Atualizar a versão no código

Troque a versão nos dois arquivos (o workflow recusa a release se não baterem com a tag):

| Arquivo | Linha |
|---|---|
| `android/chat/src/main/java/ai/atalia/chat/Atalia.kt` | `const val SDK_VERSION = "x.y.z"` |
| `ios/Sources/AtaliaChat/Atalia.swift` | `public nonisolated static let sdkVersion = "x.y.z"` |
| `react-native/package.json` | `"version": "x.y.z"` (o Android dele usa o `ai.atalia:chat` desta mesma versão) |

Os três arquivos acima, mais o `react-native/package-lock.json` (rode `npm install` na pasta `react-native`). Descreva as mudanças no [`CHANGELOG.md`](CHANGELOG.md) e troque a versão do `implementation(...)` no README. Faça o commit e o push.

## 2. Criar o release no GitHub

1. Abra **Releases › Draft a new release**.
2. Em **Choose a tag**, digite a versão (`0.2.0`, sem "v") e crie a tag.
3. Título igual à versão; cole as notas do CHANGELOG.
4. **Publish release.**

## 3. Acompanhar o workflow

Na aba **Actions**, o workflow **Release**:

1. confere a versão;
2. roda os testes do iOS e do Android;
3. assina e sobe o pacote Android.

Se falhar antes do último passo, nada foi publicado. Para tentar de novo:

1. corrija o problema;
2. apague o release e a tag;
3. crie os dois de novo.

## 4. Publicar no Maven Central

1. Entre em [central.sonatype.com](https://central.sonatype.com) › **Deployments**.
2. A versão aparece como **Validated**. Confira os arquivos e clique em **Publish**.
3. Em até ~30 minutos `ai.atalia:chat:<versão>` fica disponível para o Gradle.

Antes do **Publish** ainda dá para voltar atrás: **Drop** descarta o envio, e a mesma versão pode ser enviada de novo. Se o portal mostrar **Failed** (assinatura, POM), leia o motivo, clique em **Drop**, corrija e refaça o release.

## 5. Publicar no npm (React Native)

Só depois que o Maven Central mostrar a versão (passo 4): o pacote React Native usa no Android o `ai.atalia:chat` da mesma versão, e publicar antes quebraria o build de quem instalasse na hora.

1. Aba **Actions** › **Publicar no npm** › **Run workflow**, com a versão (`0.3.0`).
2. O workflow confere que a versão já está no Maven Central, roda os testes e publica `@atalia/react-native-chat` com procedência (provenance).

No npm também nada se apaga depois de 72 horas: versão errada se corrige com a próxima.

## Secrets do repositório (uma vez só)

Em **Settings › Secrets and variables › Actions › New repository secret**:

| Secret | De onde vem |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | central.sonatype.com › Account › **Generate User Token**: o campo *Username* |
| `MAVEN_CENTRAL_PASSWORD` | O campo *Password* do mesmo token |
| `SIGNING_KEY` | Chave privada GPG da Atalia: `gpg --armor --export-secret-keys BAA42FEF1D0378ED` (o texto inteiro, de `-----BEGIN` a `-----END`) |
| `SIGNING_KEY_PASSWORD` | A senha dessa chave |
| `NPM_TOKEN` | npmjs.com › Access Tokens › **Generate New Token** (Granular), com permissão de escrita na organização `atalia` |

Antes do primeiro `NPM_TOKEN`, crie a organização **atalia** no npmjs.com (Add Organization, plano gratuito para pacotes públicos): é ela que dá o escopo `@atalia/`.

A parte pública da chave (`BAA42FEF1D0378ED`, `Atalia <suporte@atalia.com.br>`) está em `keyserver.ubuntu.com`, onde o Maven Central confere as assinaturas.
