import clsx from 'clsx';
import Heading from '@theme/Heading';
import styles from './styles.module.css';

type FeatureItem = {
  title: string;
  icon: string;
  description: JSX.Element;
};

const FeatureList: FeatureItem[] = [
  {
    title: 'Zero Native Dependencies',
    icon: '☕',
    description: (
      <>
        Run WebAssembly anywhere the JVM runs — no JNI, no native libraries,
        no platform-specific binaries. Ship a single JAR to every OS and architecture.
      </>
    ),
  },
  {
    title: 'Sandboxed by Default',
    icon: '🔒',
    description: (
      <>
        Wasm modules execute in an isolated sandbox with no ambient capabilities.
        Your host controls what the guest can access — memory, files, and system calls.
      </>
    ),
  },
  {
    title: 'Drop-in Integration',
    icon: '🧩',
    description: (
      <>
        Add a single Maven dependency to embed Wasm in your Java app.
        Choose between interpreter, runtime compiler, or build-time compiler
        depending on your needs.
      </>
    ),
  },
];

function Feature({title, icon, description}: FeatureItem) {
  return (
    <div className={clsx('col col--4')}>
      <div className="text--center">
        <span className={styles.featureIcon} role="img">{icon}</span>
      </div>
      <div className="text--center padding-horiz--md">
        <Heading as="h3">{title}</Heading>
        <p>{description}</p>
      </div>
    </div>
  );
}

export default function HomepageFeatures(): JSX.Element {
  return (
    <section className={styles.features}>
      <div className="container">
        <div className="row">
          {FeatureList.map((props, idx) => (
            <Feature key={idx} {...props} />
          ))}
        </div>
      </div>
    </section>
  );
}
