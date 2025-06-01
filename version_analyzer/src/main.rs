use std::{
    collections::{BTreeMap, HashMap, hash_map::Entry},
    time::Duration,
};

use anyhow::Result;
use csv::Reader;
use serde::Deserialize;
use time::PrimitiveDateTime;
use tracing::info;

#[derive(Debug, Deserialize)]
struct Record {
    version: String,
    artifact_id: u32,
    #[serde(with = "time_format")]
    release_date: PrimitiveDateTime,
    community: u32,
}

time::serde::format_description!(
    time_format,
    PrimitiveDateTime,
    "[year]-[month]-[day] [hour]:[minute]:[second][optional [.[subsecond]]]"
);

fn main() -> Result<()> {
    tracing_subscriber::fmt::init();

    info!("starting reading CSV");

    let mut communities: HashMap<_, BTreeMap<_, _>> = HashMap::new();
    let mut rdr = Reader::from_path("../releases.csv")?;
    for result in rdr.deserialize() {
        let record: Record = result?;
        let versions = communities.entry(record.community).or_default();
        versions.insert(record.release_date, record);
    }

    info!("finished reading CSV");

    let mut outputs = Vec::new();

    for community in communities.values() {
        let mut current_versions = HashMap::new();
        let mut version_counts: HashMap<_, u32> = HashMap::new();
        let values: Vec<_> = community.values().collect();
        let min = values.first().unwrap().release_date;
        let max = values.last().unwrap().release_date;
        let duration = max - min;
        if duration < Duration::from_secs(1) {
            continue;
        }
        let mut invalid_duration = Duration::default();
        let mut sum = 0.0;
        for window in values.windows(2) {
            let start = window[0];
            let end = window[1];
            let window_duration = end.release_date - start.release_date;
            if let Some(prev) = current_versions.insert(start.artifact_id, &start.version) {
                if let Entry::Occupied(mut entry) = version_counts.entry(prev) {
                    if entry.get() == &1 {
                        entry.remove();
                    } else {
                        *entry.get_mut() -= 1;
                    }
                }
            }
            *version_counts.entry(&start.version).or_default() += 1;
            if current_versions.len() <= 1 {
                invalid_duration += window_duration;
                continue;
            }
            let score =
                1.0 - ((version_counts.len() - 1) as f64) / ((current_versions.len() - 1) as f64);
            let weighted = score * (window_duration / duration);
            sum += weighted;
        }
        sum /= 1.0 - (invalid_duration / duration);
        outputs.push(sum);
    }

    info!("finished calculating scores");

    let mut wtr = csv::Writer::from_path("../consistency_scores.csv")?;
    wtr.write_record(&["score"])?;

    for output in outputs {
        wtr.write_record(&[output.to_string()])?;
    }

    info!("finished writing scores");

    Ok(())
}
