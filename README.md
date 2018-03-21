Mammut
======

Small distributed ripoff of mastodon / twitter.

Running
=======

Get a release of [tendermint](https://www.tendermint.com/downloads). Add it to your path. Run
```
env TMHOME=$PWD/.tendermint tendermint init
```
to initialise the blockchain. Run `sbt server/run` to start the server. Start tendermint with
```
env TMHOME=$PWD/.tendermint tendermint node --abci=grpc
```
to start tendermint. The server _must_ be up and listening before tendermint starts. Eventually we will make the server manage the process.
Clients are run with `sbt client/run`






