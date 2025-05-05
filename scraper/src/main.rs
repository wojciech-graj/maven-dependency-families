use std::{
    env,
    io::{self, Write},
    sync::Arc,
};

use anyhow::{Result, anyhow};
use async_channel::{Receiver, Sender};
use futures_util::{StreamExt, TryStreamExt};
use indicatif::ProgressBar;
use reqwest::{Client, StatusCode, Url};
use sqlx::{FromRow, PgPool, Postgres, QueryBuilder, postgres::PgPoolOptions};
use tokio::task::JoinSet;
use tracing::{Level, warn};

const CHANNEL_SIZE: usize = 128;
const BATCH_SIZE: usize = 128;
const CLIENT_COUNT: usize = 64;
const MAVEN_URL: &str = "https://maven-central.storage.googleapis.com/maven2";

#[derive(Debug, FromRow)]
struct Version {
    version_id: i32,
    artifact_id: String,
    group_id: String,
    version: String,
}

#[derive(Debug, FromRow)]
struct Count {
    count: Option<i64>,
}

struct ProgressWriter(Arc<ProgressBar>);

impl Write for ProgressWriter {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.0.println(String::from_utf8_lossy(buf));
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

async fn snd_task(
    pool: PgPool,
    snd: Sender<Vec<Version>>,
    progress: Arc<ProgressBar>,
) -> Result<()> {
    let mut conn = pool.acquire().await?;
    let count = sqlx::query_as!(
        Count,
        "
SELECT
    count(*)
FROM
    versions
"
    )
    .fetch_one(&mut *conn)
    .await?;
    progress.set_length(
        count
            .count
            .ok_or_else(|| anyhow!("failed to get version count"))?
            .try_into()?,
    );
    let mut rows = sqlx::query_as!(
        Version,
        "
SELECT
    artifacts.group_id,
    artifacts.artifact_id,
    versions.version,
    versions.id AS version_id
FROM
    artifacts
    JOIN versions ON versions.artifact_id = artifacts.id
"
    )
    .fetch(&mut *conn)
    .try_chunks(BATCH_SIZE);
    while let Some(batch) = rows.next().await {
        snd.send(batch?).await?;
    }
    Ok(())
}

async fn rcv_task(
    pool: PgPool,
    rcv: Receiver<Vec<Version>>,
    progress: Arc<ProgressBar>,
) -> Result<()> {
    let client = Client::new();
    let mut conn = pool.acquire().await?;
    let base_url = Url::parse(MAVEN_URL)?;
    while let Ok(rows) = rcv.recv().await {
        let mut values = Vec::with_capacity(BATCH_SIZE);
        for row in rows {
            progress.inc(1);
            let mut url = base_url.clone();
            {
                let mut segments = url
                    .path_segments_mut()
                    .map_err(|_| anyhow!("url is not base"))?;
                for segment in row.group_id.split('.') {
                    segments.push(segment);
                }
                segments.push(&row.artifact_id);
                segments.push(&row.version);
                segments.push(&format!("{}-{}.pom", row.artifact_id, row.version));
            }
            let resp = client.get(url.clone()).send().await?;
            if resp.status() == StatusCode::NOT_FOUND {
                warn!("failed to find pom for {url}");
                continue;
            }
            let resp = resp.error_for_status()?.text().await?;
            values.push((row.version_id, resp));
        }
        let mut query: QueryBuilder<Postgres> =
            QueryBuilder::new("INSERT INTO poms(version_id, value) ");
        query.push_values(&values, |mut b, x| {
            b.push_bind(x.0).push_bind(&x.1);
        });
        let query = query.build().persistent(false);
        query.execute(&mut *conn).await?;
    }
    Ok(())
}

#[tokio::main]
async fn main() -> Result<()> {
    let progress = Arc::new(ProgressBar::new(0));
    {
        let progress = progress.clone();
        tracing::subscriber::set_global_default(
            tracing_subscriber::fmt()
                .with_writer(move || ProgressWriter(progress.clone()))
                .with_max_level(Level::INFO)
                .finish(),
        )?;
    }
    dotenvy::dotenv()?;
    let pool = PgPoolOptions::new()
        .max_connections(u32::try_from(CLIENT_COUNT)? + 2)
        .connect(&env::var("DATABASE_URL")?)
        .await?;
    let (snd, rcv) = async_channel::bounded(CHANNEL_SIZE);
    let mut tasks = JoinSet::new();

    {
        let pool = pool.clone();
        let progress = progress.clone();
        tasks.spawn(async move { snd_task(pool, snd, progress).await });
    }

    for _ in 0..CLIENT_COUNT {
        let pool = pool.clone();
        let rcv = rcv.clone();
        let progress = progress.clone();
        tasks.spawn(async move { rcv_task(pool, rcv, progress).await });
    }

    while let Some(res) = tasks.join_next().await {
        res??;
    }

    Ok(())
}
